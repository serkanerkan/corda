package com.r3corda.protocols

import co.paralleluniverse.fibers.Suspendable
import com.r3corda.core.TransientProperty
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.DigitalSignature
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.signWithECDSA
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.node.recordTransactions
import com.r3corda.core.node.services.ServiceType
import com.r3corda.core.protocols.ProtocolLogic
import com.r3corda.core.seconds
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.transactions.TransactionBuilder
import com.r3corda.core.transactions.WireTransaction
import com.r3corda.core.utilities.ProgressTracker
import com.r3corda.core.utilities.UntrustworthyData
import com.r3corda.core.utilities.trace
import java.math.BigDecimal
import java.security.KeyPair
import java.security.PublicKey

/**
 * Classes for manipulating a two party deal or agreement.
 *
 * TODO: The subclasses should probably be broken out into individual protocols rather than making this an ever expanding collection of subclasses.
 *
 * TODO: Also, the term Deal is used here where we might prefer Agreement.
 *
 * TODO: Consider whether we can merge this with [TwoPartyTradeProtocol]
 *
 */
object TwoPartyDealProtocol {

    class DealMismatchException(val expectedDeal: ContractState, val actualDeal: ContractState) : Exception() {
        override fun toString() = "The submitted deal didn't match the expected: $expectedDeal vs $actualDeal"
    }

    class DealRefMismatchException(val expectedDeal: StateRef, val actualDeal: StateRef) : Exception() {
        override fun toString() = "The submitted deal didn't match the expected: $expectedDeal vs $actualDeal"
    }

    // This object is serialised to the network and is the first protocol message the seller sends to the buyer.
    data class Handshake<out T>(val payload: T, val publicKey: PublicKey)

    class SignaturesFromPrimary(val sellerSig: DigitalSignature.WithKey, val notarySig: DigitalSignature.LegallyIdentifiable)

    /**
     * [Primary] at the end sends the signed tx to all the regulator parties. This a seperate workflow which needs a
     * sepearate session with the regulator. This interface is used to do that in [Primary.getCounterpartyMarker].
     */
    interface MarkerForBogusRegulatorProtocol

    /**
     * Abstracted bilateral deal protocol participant that initiates communication/handshake.
     *
     * There's a good chance we can push at least some of this logic down into core protocol logic
     * and helper methods etc.
     */
    abstract class Primary(override val progressTracker: ProgressTracker = Primary.tracker()) : ProtocolLogic<SignedTransaction>() {

        companion object {
            object AWAITING_PROPOSAL : ProgressTracker.Step("Handshaking and awaiting transaction proposal")
            object VERIFYING : ProgressTracker.Step("Verifying proposed transaction")
            object SIGNING : ProgressTracker.Step("Signing transaction")
            object NOTARY : ProgressTracker.Step("Getting notary signature")
            object SENDING_SIGS : ProgressTracker.Step("Sending transaction signatures to other party")
            object RECORDING : ProgressTracker.Step("Recording completed transaction")
            object COPYING_TO_REGULATOR : ProgressTracker.Step("Copying regulator")

            fun tracker() = ProgressTracker(AWAITING_PROPOSAL, VERIFYING, SIGNING, NOTARY, SENDING_SIGS, RECORDING, COPYING_TO_REGULATOR)
        }

        abstract val payload: Any
        abstract val notaryNode: NodeInfo
        abstract val otherParty: Party
        abstract val myKeyPair: KeyPair

        override fun getCounterpartyMarker(party: Party): Class<*> {
            return if (serviceHub.networkMapCache.regulators.any { it.legalIdentity == party }) {
                MarkerForBogusRegulatorProtocol::class.java
            } else {
                super.getCounterpartyMarker(party)
            }
        }

        @Suspendable
        fun getPartialTransaction(): UntrustworthyData<SignedTransaction> {
            progressTracker.currentStep = AWAITING_PROPOSAL

            // Make the first message we'll send to kick off the protocol.
            val hello = Handshake(payload, myKeyPair.public)
            val maybeSTX = sendAndReceive<SignedTransaction>(otherParty, hello)

            return maybeSTX
        }

        @Suspendable
        fun verifyPartialTransaction(untrustedPartialTX: UntrustworthyData<SignedTransaction>): SignedTransaction {
            progressTracker.currentStep = VERIFYING

            untrustedPartialTX.unwrap { stx ->
                progressTracker.nextStep()

                // Check that the tx proposed by the buyer is valid.
                val wtx: WireTransaction = stx.verifySignatures(myKeyPair.public, notaryNode.notaryIdentity.owningKey)
                logger.trace { "Received partially signed transaction: ${stx.id}" }

                checkDependencies(stx)

                // This verifies that the transaction is contract-valid, even though it is missing signatures.
                wtx.toLedgerTransaction(serviceHub).verify()

                // There are all sorts of funny games a malicious secondary might play here, we should fix them:
                //
                // - This tx may attempt to send some assets we aren't intending to sell to the secondary, if
                //   we're reusing keys! So don't reuse keys!
                // - This tx may include output states that impose odd conditions on the movement of the cash,
                //   once we implement state pairing.
                //
                // but the goal of this code is not to be fully secure (yet), but rather, just to find good ways to
                // express protocol state machines on top of the messaging layer.

                return stx
            }
        }

        @Suspendable
        private fun checkDependencies(stx: SignedTransaction) {
            // Download and check all the transactions that this transaction depends on, but do not check this
            // transaction itself.
            val dependencyTxIDs = stx.tx.inputs.map { it.txhash }.toSet()
            subProtocol(ResolveTransactionsProtocol(dependencyTxIDs, otherParty))
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val stx: SignedTransaction = verifyPartialTransaction(getPartialTransaction())

            // These two steps could be done in parallel, in theory. Our framework doesn't support that yet though.
            val ourSignature = signWithOurKey(stx)
            val notarySignature = getNotarySignature(stx)

            val fullySigned = sendSignatures(stx, ourSignature, notarySignature)

            progressTracker.currentStep = RECORDING

            serviceHub.recordTransactions(fullySigned)

            logger.trace { "Deal stored" }

            progressTracker.currentStep = COPYING_TO_REGULATOR
            val regulators = serviceHub.networkMapCache.regulators
            if (regulators.isNotEmpty()) {
                // Copy the transaction to every regulator in the network. This is obviously completely bogus, it's
                // just for demo purposes.
                regulators.forEach { send(it.serviceIdentities(ServiceType.regulator).first(), fullySigned) }
            }

            return fullySigned
        }

        @Suspendable
        private fun getNotarySignature(stx: SignedTransaction): DigitalSignature.LegallyIdentifiable {
            progressTracker.currentStep = NOTARY
            return subProtocol(NotaryProtocol.Client(stx))
        }

        open fun signWithOurKey(partialTX: SignedTransaction): DigitalSignature.WithKey {
            progressTracker.currentStep = SIGNING
            return myKeyPair.signWithECDSA(partialTX.txBits)
        }

        @Suspendable
        private fun sendSignatures(partialTX: SignedTransaction, ourSignature: DigitalSignature.WithKey,
                                   notarySignature: DigitalSignature.LegallyIdentifiable): SignedTransaction {
            progressTracker.currentStep = SENDING_SIGS
            val fullySigned = partialTX + ourSignature + notarySignature

            logger.trace { "Built finished transaction, sending back to other party!" }

            send(otherParty, SignaturesFromPrimary(ourSignature, notarySignature))
            return fullySigned
        }
    }


    /**
     * Abstracted bilateral deal protocol participant that is recipient of initial communication.
     *
     * There's a good chance we can push at least some of this logic down into core protocol logic
     * and helper methods etc.
     */
    abstract class Secondary<U>(override val progressTracker: ProgressTracker = Secondary.tracker()) : ProtocolLogic<SignedTransaction>() {

        companion object {
            object RECEIVING : ProgressTracker.Step("Waiting for deal info")
            object VERIFYING : ProgressTracker.Step("Verifying deal info")
            object SIGNING : ProgressTracker.Step("Generating and signing transaction proposal")
            object SWAPPING_SIGNATURES : ProgressTracker.Step("Swapping signatures with the other party")
            object RECORDING : ProgressTracker.Step("Recording completed transaction")

            fun tracker() = ProgressTracker(RECEIVING, VERIFYING, SIGNING, SWAPPING_SIGNATURES, RECORDING)
        }

        abstract val otherParty: Party

        @Suspendable
        override fun call(): SignedTransaction {
            val handshake = receiveAndValidateHandshake()

            progressTracker.currentStep = SIGNING
            val (ptx, additionalSigningPubKeys) = assembleSharedTX(handshake)
            val stx = signWithOurKeys(additionalSigningPubKeys, ptx)

            val signatures = swapSignaturesWithPrimary(stx)

            logger.trace { "Got signatures from other party, verifying ... " }

            val fullySigned = stx + signatures.sellerSig + signatures.notarySig
            fullySigned.verifySignatures()

            logger.trace { "Signatures received are valid. Deal transaction complete! :-)" }

            progressTracker.currentStep = RECORDING
            serviceHub.recordTransactions(fullySigned)

            logger.trace { "Deal transaction stored" }
            return fullySigned
        }

        @Suspendable
        private fun receiveAndValidateHandshake(): Handshake<U> {
            progressTracker.currentStep = RECEIVING
            // Wait for a trade request to come in on our pre-provided session ID.
            val handshake = receive<Handshake<U>>(otherParty)

            progressTracker.currentStep = VERIFYING
            return handshake.unwrap { validateHandshake(it) }
        }

        @Suspendable
        private fun swapSignaturesWithPrimary(stx: SignedTransaction): SignaturesFromPrimary {
            progressTracker.currentStep = SWAPPING_SIGNATURES
            logger.trace { "Sending partially signed transaction to other party" }

            // TODO: Protect against the seller terminating here and leaving us in the lurch without the final tx.

            return sendAndReceive<SignaturesFromPrimary>(otherParty, stx).unwrap { it }
        }

        private fun signWithOurKeys(signingPubKeys: List<PublicKey>, ptx: TransactionBuilder): SignedTransaction {
            // Now sign the transaction with whatever keys we need to move the cash.
            for (k in signingPubKeys) {
                val priv = serviceHub.keyManagementService.toPrivate(k)
                ptx.signWith(KeyPair(k, priv))
            }

            return ptx.toSignedTransaction(checkSufficientSignatures = false)
        }

        @Suspendable protected abstract fun validateHandshake(handshake: Handshake<U>): Handshake<U>
        @Suspendable protected abstract fun assembleSharedTX(handshake: Handshake<U>): Pair<TransactionBuilder, List<PublicKey>>
    }


    data class AutoOffer(val notary: Party, val dealBeingOffered: DealState)


    /**
     * One side of the protocol for inserting a pre-agreed deal.
     */
    open class Instigator(override val otherParty: Party,
                          override val payload: AutoOffer,
                          override val myKeyPair: KeyPair,
                          override val progressTracker: ProgressTracker = Primary.tracker()) : Primary() {

        override val notaryNode: NodeInfo get() =
            serviceHub.networkMapCache.notaryNodes.filter { it.notaryIdentity == payload.notary }.single()
    }

    /**
     * One side of the protocol for inserting a pre-agreed deal.
     */
    open class Acceptor(override val otherParty: Party,
                        override val progressTracker: ProgressTracker = Secondary.tracker()) : Secondary<AutoOffer>() {

        override fun validateHandshake(handshake: Handshake<AutoOffer>): Handshake<AutoOffer> {
            // What is the seller trying to sell us?
            val autoOffer = handshake.payload
            val deal = autoOffer.dealBeingOffered
            logger.trace { "Got deal request for: ${deal.ref}" }
            return handshake.copy(payload = autoOffer.copy(dealBeingOffered = deal))
        }

        override fun assembleSharedTX(handshake: Handshake<AutoOffer>): Pair<TransactionBuilder, List<PublicKey>> {
            val deal = handshake.payload.dealBeingOffered
            val ptx = deal.generateAgreement(handshake.payload.notary)

            // And add a request for timestamping: it may be that none of the contracts need this! But it can't hurt
            // to have one.
            ptx.setTime(serviceHub.clock.instant(), 30.seconds)
            return Pair(ptx, arrayListOf(deal.parties.single { it.name == serviceHub.myInfo.legalIdentity.name }.owningKey))
        }
    }

    /**
     * One side of the fixing protocol for an interest rate swap, but could easily be generalised further.
     *
     * Do not infer too much from the name of the class.  This is just to indicate that it is the "side"
     * of the protocol that is run by the party with the fixed leg of swap deal, which is the basis for deciding
     * who does what in the protocol.
     */
    class Fixer(override val otherParty: Party,
                override val progressTracker: ProgressTracker = Secondary.tracker()) : Secondary<FixingSession>() {

        private lateinit var txState: TransactionState<*>
        private lateinit var deal: FixableDealState

        override fun validateHandshake(handshake: Handshake<FixingSession>): Handshake<FixingSession> {
            logger.trace { "Got fixing request for: ${handshake.payload}" }

            txState = serviceHub.loadState(handshake.payload.ref)
            deal = txState.data as FixableDealState

            // validate the party that initiated is the one on the deal and that the recipient corresponds with it.
            // TODO: this is in no way secure and will be replaced by general session initiation logic in the future
            val myName = serviceHub.myInfo.legalIdentity.name
            // Also check we are one of the parties
            deal.parties.filter { it.name == myName }.single()

            return handshake
        }

        @Suspendable
        override fun assembleSharedTX(handshake: Handshake<FixingSession>): Pair<TransactionBuilder, List<PublicKey>> {
            @Suppress("UNCHECKED_CAST")
            val fixOf = deal.nextFixingOf()!!

            // TODO Do we need/want to substitute in new public keys for the Parties?
            val myName = serviceHub.myInfo.legalIdentity.name
            val myOldParty = deal.parties.single { it.name == myName }

            val newDeal = deal

            val ptx = TransactionType.General.Builder(txState.notary)

            val oracle = serviceHub.networkMapCache.get(handshake.payload.oracleType).first()

            val addFixing = object : RatesFixProtocol(ptx, oracle.serviceIdentities(handshake.payload.oracleType).first(), fixOf, BigDecimal.ZERO, BigDecimal.ONE) {
                @Suspendable
                override fun beforeSigning(fix: Fix) {
                    newDeal.generateFix(ptx, StateAndRef(txState, handshake.payload.ref), fix)

                    // And add a request for timestamping: it may be that none of the contracts need this! But it can't hurt
                    // to have one.
                    ptx.setTime(serviceHub.clock.instant(), 30.seconds)
                }
            }
            subProtocol(addFixing)
            return Pair(ptx, arrayListOf(myOldParty.owningKey))
        }
    }

    /**
     * One side of the fixing protocol for an interest rate swap, but could easily be generalised furher.
     *
     * As per the [Fixer], do not infer too much from this class name in terms of business roles.  This
     * is just the "side" of the protocol run by the party with the floating leg as a way of deciding who
     * does what in the protocol.
     */
    class Floater(override val otherParty: Party,
                  override val payload: FixingSession,
                  override val progressTracker: ProgressTracker = Primary.tracker()) : Primary() {

        @Suppress("UNCHECKED_CAST")
        internal val dealToFix: StateAndRef<FixableDealState> by TransientProperty {
            val state = serviceHub.loadState(payload.ref) as TransactionState<FixableDealState>
            StateAndRef(state, payload.ref)
        }

        override val myKeyPair: KeyPair get() {
            val myName = serviceHub.myInfo.legalIdentity.name
            val publicKey = dealToFix.state.data.parties.filter { it.name == myName }.single().owningKey
            return serviceHub.keyManagementService.toKeyPair(publicKey)
        }

        override val notaryNode: NodeInfo get() =
            serviceHub.networkMapCache.notaryNodes.filter { it.notaryIdentity == dealToFix.state.notary }.single()
    }


    /** Used to set up the session between [Floater] and [Fixer] */
    data class FixingSession(val ref: StateRef, val oracleType: ServiceType)

    /**
     * This protocol looks at the deal and decides whether to be the Fixer or Floater role in agreeing a fixing.
     *
     * It is kicked off as an activity on both participant nodes by the scheduler when it's time for a fixing.  If the
     * Fixer role is chosen, then that will be initiated by the [FixingSession] message sent from the other party and
     * handled by the [FixingSessionInitiationHandler].
     *
     * TODO: Replace [FixingSession] and [FixingSessionInitiationHandler] with generic session initiation logic once it exists.
     */
    class FixingRoleDecider(val ref: StateRef,
                            override val progressTracker: ProgressTracker = tracker()) : ProtocolLogic<Unit>() {

        companion object {
            class LOADING() : ProgressTracker.Step("Loading state to decide fixing role")

            fun tracker() = ProgressTracker(LOADING())
        }

        @Suspendable
        override fun call(): Unit {
            progressTracker.nextStep()
            val dealToFix = serviceHub.loadState(ref)
            // TODO: this is not the eventual mechanism for identifying the parties
            val fixableDeal = (dealToFix.data as FixableDealState)
            val sortedParties = fixableDeal.parties.sortedBy { it.name }
            if (sortedParties[0].name == serviceHub.myInfo.legalIdentity.name) {
                val fixing = FixingSession(ref, fixableDeal.oracleType)
                // Start the Floater which will then kick-off the Fixer
                subProtocol(Floater(sortedParties[1], fixing))
            }
        }
    }

}
