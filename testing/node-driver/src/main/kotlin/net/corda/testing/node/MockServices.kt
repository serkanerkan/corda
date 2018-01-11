package net.corda.testing.node

import com.google.common.collect.MutableClassToInstanceMap
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.concurrent.CordaFuture
import net.corda.core.cordapp.CordappProvider
import net.corda.core.crypto.*
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.concurrent.doneFuture
import net.corda.core.messaging.DataFeed
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.FlowProgressHandle
import net.corda.core.node.*
import net.corda.core.node.services.*
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.toFuture
import net.corda.core.transactions.SignedTransaction
import net.corda.node.VersionInfo
import net.corda.node.internal.configureDatabase
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.services.api.IdentityServiceInternal
import net.corda.node.services.api.SchemaService
import net.corda.node.services.api.VaultServiceInternal
import net.corda.node.services.api.WritableTransactionStorage
import net.corda.node.services.config.configOf
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.node.services.keys.freshCertificate
import net.corda.node.services.keys.getSigner
import net.corda.node.services.schema.HibernateObserver
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.transactions.InMemoryTransactionVerifierService
import net.corda.node.services.vault.NodeVaultService
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.HibernateConfiguration
import net.corda.nodeapi.internal.persistence.TransactionIsolationLevel
import net.corda.testing.DEV_ROOT_CA
import net.corda.testing.TestIdentity
import net.corda.testing.database.DatabaseConstants
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_CLASSNAME
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_PASSWORD
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_URL
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_USER
import net.corda.testing.database.DatabaseConstants.SCHEMA
import net.corda.testing.database.DatabaseConstants.TRANSACTION_ISOLATION_LEVEL
import net.corda.testing.services.MockAttachmentStorage
import net.corda.testing.services.MockCordappProvider
import org.bouncycastle.operator.ContentSigner
import rx.Observable
import rx.subjects.PublishSubject
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.sql.Connection
import java.time.Clock
import java.util.*

fun makeTestIdentityService(vararg identities: PartyAndCertificate) = InMemoryIdentityService(identities, DEV_ROOT_CA.certificate)
/**
 * A singleton utility that only provides a mock identity, key and storage service. However, this is sufficient for
 * building chains of transactions and verifying them. It isn't sufficient for testing flows however.
 */
open class MockServices private constructor(
        cordappLoader: CordappLoader,
        override val validatedTransactions: WritableTransactionStorage,
        override val identityService: IdentityServiceInternal,
        private val initialIdentity: TestIdentity,
        private val moreKeys: Array<out KeyPair>
) : ServiceHub, StateLoader by validatedTransactions {
    companion object {
        @JvmStatic
        val MOCK_VERSION_INFO = VersionInfo(1, "Mock release", "Mock revision", "Mock Vendor")

        /**
         * Make properties appropriate for creating a DataSource for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database or database username/schema.  Defaults to a random string.
         * @param nodeNameExtension Provides additional name extension for the "instance" of in-memory database to provide uniqueness when running unit tests against H2 db.
         * @param configSupplier returns [Config] with dataSourceProperties entry.
         */
        // TODO: Can we use an X509 principal generator here?
        @JvmStatic
        fun makeTestDataSourceProperties(nodeName: String = SecureHash.randomSHA256().toString(), nodeNameExtension: String? = null,
                                         configSupplier: (String, String?) -> Config = ::databaseProviderDataSourceConfig): Properties {
            val config = configSupplier(nodeName, nodeNameExtension)
            val props = Properties()
            props.setProperty("dataSourceClassName", config.getString("dataSourceProperties.dataSourceClassName"))
            props.setProperty("dataSource.url", config.getString("dataSourceProperties.dataSource.url"))
            props.setProperty("dataSource.user", config.getString("dataSourceProperties.dataSource.user"))
            props.setProperty("dataSource.password", config.getString("dataSourceProperties.dataSource.password"))
            props["autoCommit"] = false
            return props
        }

        /**
         * Make properties appropriate for creating a Database for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database or database username/schema.
         */
        @JvmStatic
        fun makeTestDatabaseProperties(nodeName: String? = null): DatabaseConfig {
            val config = databaseProviderDataSourceConfig(nodeName)
            val transactionIsolationLevel = if (config.hasPath(TRANSACTION_ISOLATION_LEVEL)) TransactionIsolationLevel.valueOf(config.getString(TRANSACTION_ISOLATION_LEVEL))
                                                else TransactionIsolationLevel.READ_COMMITTED
            val schema = if (config.hasPath(SCHEMA)) config.getString(SCHEMA) else ""
            return DatabaseConfig(runMigration = true, transactionIsolationLevel = transactionIsolationLevel, schema = schema)
        }

        /**
         * Makes database and mock services appropriate for unit tests.
         * @param moreKeys a list of additional [KeyPair] instances to be used by [MockServices].
         * @param identityService an instance of [IdentityServiceInternal], see [makeTestIdentityService].
         * @param initialIdentity the first (typically sole) identity the services will represent.
         * @return a pair where the first element is the instance of [CordaPersistence] and the second is [MockServices].
         */
        @JvmStatic
        fun makeTestDatabaseAndMockServices(cordappPackages: List<String>,
                                            identityService: IdentityServiceInternal,
                                            initialIdentity: TestIdentity,
                                            vararg moreKeys: KeyPair): Pair<CordaPersistence, MockServices> {
            val cordappLoader = CordappLoader.createWithTestPackages(cordappPackages)
            val dataSourceProps = makeTestDataSourceProperties(initialIdentity.name.organisation)
            val schemaService = NodeSchemaService(cordappLoader.cordappSchemas)
            val database = configureDatabase(dataSourceProps, makeTestDatabaseProperties(initialIdentity.name.organisation), identityService, schemaService)
            val mockService = database.transaction {
                object : MockServices(cordappLoader, identityService, initialIdentity, moreKeys) {
                    override val vaultService: VaultServiceInternal = makeVaultService(database.hibernateConfig, schemaService)

                    override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
                        super.recordTransactions(statesToRecord, txs)
                        // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                        vaultService.notifyAll(statesToRecord, txs.map { it.tx })
                    }

                    override fun jdbcSession(): Connection = database.createSession()
                }
            }
            return Pair(database, mockService)
        }
    }

    private constructor(cordappLoader: CordappLoader, identityService: IdentityServiceInternal, initialIdentity: TestIdentity, moreKeys: Array<out KeyPair>) : this(cordappLoader, MockTransactionStorage(), identityService, initialIdentity, moreKeys)
    @JvmOverloads
    constructor(cordappPackages: List<String>, identityService: IdentityServiceInternal = makeTestIdentityService(), initialIdentity: TestIdentity, vararg moreKeys: KeyPair) : this(CordappLoader.createWithTestPackages(cordappPackages), identityService, initialIdentity, moreKeys)

    @JvmOverloads
    constructor(cordappPackages: List<String>, identityService: IdentityServiceInternal = makeTestIdentityService(), initialIdentityName: CordaX500Name, key: KeyPair, vararg moreKeys: KeyPair) : this(cordappPackages, identityService, TestIdentity(initialIdentityName, key), *moreKeys)

    @JvmOverloads
    constructor(cordappPackages: List<String>, identityService: IdentityServiceInternal = makeTestIdentityService(), initialIdentityName: CordaX500Name) : this(cordappPackages, identityService, TestIdentity(initialIdentityName))

    @JvmOverloads
    constructor(cordappPackages: List<String>, identityService: IdentityServiceInternal = makeTestIdentityService(), vararg moreKeys: KeyPair) : this(cordappPackages, identityService, TestIdentity.fresh("MockServices"), *moreKeys)

    override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
        txs.forEach {
            validatedTransactions.addTransaction(it)
        }
    }

    final override val attachments = MockAttachmentStorage()
    override val keyManagementService: KeyManagementService by lazy { MockKeyManagementService(identityService, *arrayOf(initialIdentity.keyPair) + moreKeys) }
    override val vaultService: VaultService get() = throw UnsupportedOperationException()
    override val contractUpgradeService: ContractUpgradeService get() = throw UnsupportedOperationException()
    override val networkMapCache: NetworkMapCache get() = throw UnsupportedOperationException()
    override val clock: Clock get() = Clock.systemUTC()
    override val myInfo: NodeInfo
        get() {
            return NodeInfo(emptyList(), listOf(initialIdentity.identity), 1, serial = 1L)
        }
    override val transactionVerifierService: TransactionVerifierService get() = InMemoryTransactionVerifierService(2)
    val mockCordappProvider = MockCordappProvider(cordappLoader, attachments)
    override val cordappProvider: CordappProvider get() = mockCordappProvider
    lateinit var hibernatePersister: HibernateObserver

    fun makeVaultService(hibernateConfig: HibernateConfiguration, schemaService: SchemaService): VaultServiceInternal {
        val vaultService = NodeVaultService(Clock.systemUTC(), keyManagementService, validatedTransactions, hibernateConfig)
        hibernatePersister = HibernateObserver.install(vaultService.rawUpdates, hibernateConfig, schemaService)
        return vaultService
    }

    val cordappServices: MutableClassToInstanceMap<SerializeAsToken> = MutableClassToInstanceMap.create<SerializeAsToken>()
    override fun <T : SerializeAsToken> cordaService(type: Class<T>): T {
        require(type.isAnnotationPresent(CordaService::class.java)) { "${type.name} is not a Corda service" }
        return cordappServices.getInstance(type) ?: throw IllegalArgumentException("Corda service ${type.name} does not exist")
    }

    override fun jdbcSession(): Connection = throw UnsupportedOperationException()
}

class MockKeyManagementService(val identityService: IdentityServiceInternal,
                               vararg initialKeys: KeyPair) : SingletonSerializeAsToken(), KeyManagementService {
    private val keyStore: MutableMap<PublicKey, PrivateKey> = initialKeys.associateByTo(HashMap(), { it.public }, { it.private })

    override val keys: Set<PublicKey> get() = keyStore.keys

    private val nextKeys = LinkedList<KeyPair>()

    override fun freshKey(): PublicKey {
        val k = nextKeys.poll() ?: generateKeyPair()
        keyStore[k.public] = k.private
        return k.public
    }

    override fun filterMyKeys(candidateKeys: Iterable<PublicKey>): Iterable<PublicKey> = candidateKeys.filter { it in this.keys }

    override fun freshKeyAndCert(identity: PartyAndCertificate, revocationEnabled: Boolean): PartyAndCertificate {
        return freshCertificate(identityService, freshKey(), identity, getSigner(identity.owningKey), revocationEnabled)
    }

    private fun getSigner(publicKey: PublicKey): ContentSigner = getSigner(getSigningKeyPair(publicKey))

    private fun getSigningKeyPair(publicKey: PublicKey): KeyPair {
        val pk = publicKey.keys.first { keyStore.containsKey(it) }
        return KeyPair(pk, keyStore[pk]!!)
    }

    override fun sign(bytes: ByteArray, publicKey: PublicKey): DigitalSignature.WithKey {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(bytes)
    }

    override fun sign(signableData: SignableData, publicKey: PublicKey): TransactionSignature {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(signableData)
    }
}

open class MockTransactionStorage : WritableTransactionStorage, SingletonSerializeAsToken() {
    override fun trackTransaction(id: SecureHash): CordaFuture<SignedTransaction> {
        return txns[id]?.let { doneFuture(it) } ?: _updatesPublisher.filter { it.id == id }.toFuture()
    }

    override fun track(): DataFeed<List<SignedTransaction>, SignedTransaction> {
        return DataFeed(txns.values.toList(), _updatesPublisher)
    }

    private val txns = HashMap<SecureHash, SignedTransaction>()

    private val _updatesPublisher = PublishSubject.create<SignedTransaction>()

    override val updates: Observable<SignedTransaction>
        get() = _updatesPublisher

    private fun notify(transaction: SignedTransaction) = _updatesPublisher.onNext(transaction)

    override fun addTransaction(transaction: SignedTransaction): Boolean {
        val recorded = txns.putIfAbsent(transaction.id, transaction) == null
        if (recorded) {
            notify(transaction)
        }
        return recorded
    }

    override fun getTransaction(id: SecureHash): SignedTransaction? = txns[id]
}

fun <T : SerializeAsToken> createMockCordaService(serviceHub: MockServices, serviceConstructor: (AppServiceHub) -> T): T {
    class MockAppServiceHubImpl<out T : SerializeAsToken>(val serviceHub: MockServices, serviceConstructor: (AppServiceHub) -> T) : AppServiceHub, ServiceHub by serviceHub {
        val serviceInstance: T = serviceConstructor(this)

        init {
            serviceHub.cordappServices.putInstance(serviceInstance.javaClass, serviceInstance)
        }

        override fun <T> startFlow(flow: FlowLogic<T>): FlowHandle<T> {
            throw UnsupportedOperationException()
        }

        override fun <T> startTrackedFlow(flow: FlowLogic<T>): FlowProgressHandle<T> {
            throw UnsupportedOperationException()
        }
    }
    return MockAppServiceHubImpl(serviceHub, serviceConstructor).serviceInstance
}

/**
 * Reads database and dataSource configuration from a file denoted by 'databaseProvider' system property,
 * overwitten by system properties and defaults to H2 in memory db.
 * @param nodeName Reflects the "instance" of the database username/schema, the value will be used to replace ${nodeOrganizationName} placeholder
 * if the placeholder is present in config.
 * @param postfix Additional postix added to database "instance" name in case config defaults to H2 in memory database.
 */
fun databaseProviderDataSourceConfig(nodeName: String? = null, postfix: String? = null): Config {

    val parseOptions = ConfigParseOptions.defaults()

    //read overrides from command line (passed by Gradle as system properties)
    val dataSourceKeys = listOf(DATA_SOURCE_URL, DATA_SOURCE_CLASSNAME, DATA_SOURCE_USER, DATA_SOURCE_PASSWORD)
    val dataSourceSystemProperties = Properties()
    val allSystemProperties = System.getProperties().toList().map { it.first.toString() to it.second.toString() }.toMap()
    dataSourceKeys.filter { allSystemProperties.containsKey(it) }.forEach { dataSourceSystemProperties.setProperty(it, allSystemProperties[it]) }
    val systemConfigOverride = ConfigFactory.parseProperties(dataSourceSystemProperties, parseOptions)

    //read from db vendor specific configuration file
    val databaseConfig = ConfigFactory.parseResources(System.getProperty("databaseProvider") + ".conf", parseOptions.setAllowMissing(true))
    val fixedOverride = ConfigFactory.parseString("baseDirectory = \"\"")

    //implied property nodeOrganizationName to fill the potential placeholders in db schema/ db user properties
    val standardizedNodeName = nodeName?.replace(" ", "")?.replace("-", "_")
    val nodeOrganizationNameConfig = if (standardizedNodeName != null) configOf("nodeOrganizationName" to standardizedNodeName) else ConfigFactory.empty()

    //defaults to H2
    //for H2 the same db instance runs for all integration tests, so adding additional variable postfix create a unique database each time
    val defaultConfig = inMemoryH2DataSourceConfig(standardizedNodeName, postfix)

    return systemConfigOverride.withFallback(databaseConfig)
            .withFallback(fixedOverride)
            .withFallback(nodeOrganizationNameConfig)
            .withFallback(defaultConfig)
            .resolve()
}

/**
 * Creates data source configuration for in memory H2 as it would be specified in reference.conf 'datasource' snippet.
 * @param nodeName Reflects the "instance" of the database username/schema
 * @param postfix Additional postix added to database "instance" name to add uniqueness when running integration tests.
 */
fun inMemoryH2DataSourceConfig(nodeName: String? = null, postfix: String? = null) : Config {
    val nodeName = nodeName ?: SecureHash.randomSHA256().toString()
    val h2InstanceName = if (postfix != null) nodeName + "_" + postfix else nodeName

    return ConfigFactory.parseMap(mapOf(
            DatabaseConstants.DATA_SOURCE_CLASSNAME to "org.h2.jdbcx.JdbcDataSource",
            DatabaseConstants.DATA_SOURCE_URL to "jdbc:h2:mem:${h2InstanceName}_persistence;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE",
            DatabaseConstants.DATA_SOURCE_USER to "sa",
            DatabaseConstants.DATA_SOURCE_PASSWORD to ""))
}