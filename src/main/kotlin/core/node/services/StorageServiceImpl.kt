package core.node.services

import core.ContractFactory
import core.Party
import core.SignedTransaction
import core.crypto.SecureHash
import core.utilities.RecordingMap
import org.slf4j.LoggerFactory
import java.security.KeyPair
import java.util.*

open class StorageServiceImpl(attachments: AttachmentStorage,
                              contractFactory: ContractFactory,
                              keypair: KeyPair,
                              identity: Party = Party("Unit test party", keypair.public),
                              // This parameter is for unit tests that want to observe operation details.
                              val recordingAs: (String) -> String = { tableName -> "" })
: StorageService {
    protected val tables = HashMap<String, MutableMap<Any, Any>>()

    private fun <K, V> getMapOriginal(tableName: String): MutableMap<K, V> {
        synchronized(tables) {
            return tables.getOrPut(tableName) {
                recorderWrap(Collections.synchronizedMap(HashMap<Any, Any>()), tableName);
            } as MutableMap<K, V>
        }
    }

    private fun <K, V> recorderWrap(map: MutableMap<K, V>, tableName: String): MutableMap<K, V> {
        if (recordingAs(tableName) != "")
            return RecordingMap(map, LoggerFactory.getLogger("recordingmap.${recordingAs(tableName)}"))
        else
            return map
    }

    override val validatedTransactions: MutableMap<SecureHash, SignedTransaction>
        get() = getMapOriginal("validated-transactions")
    override val stateMachines: MutableMap<SecureHash, ByteArray>
        get() = getMapOriginal("state-machines")

    override val attachments: AttachmentStorage = attachments
    override val contractPrograms = contractFactory
    override val myLegalIdentity = identity
    override val myLegalIdentityKey = keypair
}