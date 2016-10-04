package com.r3corda.protocols

import co.paralleluniverse.fibers.Suspendable
import com.r3corda.contracts.asset.Cash
import com.r3corda.contracts.asset.sumCashBy
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.DigitalSignature
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.signWithECDSA
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.node.services.ServiceType
import com.r3corda.core.protocols.ProtocolLogic
import com.r3corda.core.seconds
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.transactions.TransactionBuilder
import com.r3corda.core.transactions.WireTransaction
import com.r3corda.core.utilities.ProgressTracker
import com.r3corda.core.utilities.trace
import java.security.KeyPair
import java.security.PublicKey
import java.util.*

/**
 * This asset trading protocol implements a "delivery vs payment" type swap. It has two parties (B and S for buyer
 * and seller) and the following steps:
 *
 * 1. S sends the [StateAndRef] pointing to what they want to sell to B, along with info about the price they require
 *    B to pay. For example this has probably been agreed on an exchange.
 * 2. B sends to S a [SignedTransaction] that includes the state as input, B's cash as input, the state with the new
 *    owner key as output, and any change cash as output. It contains a single signature from B but isn't valid because
 *    it lacks a signature from S authorising movement of the asset.
 * 3. S signs it and hands the now finalised SignedWireTransaction back to B.
 *
 * Assuming no malicious termination, they both end the protocol being in posession of a valid, signed transaction
 * that represents an atomic asset swap.
 *
 * Note that it's the *seller* who initiates contact with the buyer, not vice-versa as you might imagine.
 *
 * To initiate the protocol, use either the [runBuyer] or [runSeller] methods, depending on which side of the trade
 * your node is taking. These methods return a future which will complete once the trade is over and a fully signed
 * transaction is available: you can either block your thread waiting for the protocol to complete by using
 * [ListenableFuture.get] or more usefully, register a callback that will be invoked when the time comes.
 *
 * To see an example of how to use this class, look at the unit tests.
 */
// TODO: Common elements in multi-party transaction consensus and signing should be refactored into a superclass of this
// and [AbstractStateReplacementProtocol].
object TwoPartyTradeProtocol {

    class UnacceptablePriceException(val givenPrice: Amount<Currency>) : Exception("Unacceptable price: $givenPrice")
    class AssetMismatchException(val expectedTypeName: String, val typeName: String) : Exception() {
        override fun toString() = "The submitted asset didn't match the expected type: $expectedTypeName vs $typeName"
    }

    // This object is serialised to the network and is the first protocol message the seller sends to the buyer.
    data class SellerTradeInfo(
            val assetForSale: StateAndRef<OwnableState>,
            val price: Amount<Currency>,
            val sellerOwnerKey: PublicKey
    )

    data class SignaturesFromSeller(val sellerSig: DigitalSignature.WithKey,
                                    val notarySig: DigitalSignature.LegallyIdentifiable)

    open class Seller(val otherParty: Party,
                      val notaryNode: NodeInfo,
                      val assetToSell: StateAndRef<OwnableState>,
                      val price: Amount<Currency>,
                      val myKeyPair: KeyPair,
                      override val progressTracker: ProgressTracker = Seller.tracker()) : ProtocolLogic<SignedTransaction>() {

        companion object {
            object AWAITING_PROPOSAL : ProgressTracker.Step("Awaiting transaction proposal")

            object VERIFYING : ProgressTracker.Step("Verifying transaction proposal")

            object SIGNING : ProgressTracker.Step("Signing transaction")

            object NOTARY : ProgressTracker.Step("Getting notary signature")

            object SENDING_SIGS : ProgressTracker.Step("Sending transaction signatures to buyer")

            fun tracker() = ProgressTracker(AWAITING_PROPOSAL, VERIFYING, SIGNING, NOTARY, SENDING_SIGS)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val partialTX: SignedTransaction = receiveAndCheckProposedTransaction()

            // These two steps could be done in parallel, in theory. Our framework doesn't support that yet though.
            val ourSignature = signWithOurKey(partialTX)
            val notarySignature = getNotarySignature(partialTX)
            return sendSignatures(partialTX, ourSignature, notarySignature)
        }

        @Suspendable
        private fun getNotarySignature(stx: SignedTransaction): DigitalSignature.LegallyIdentifiable {
            progressTracker.currentStep = NOTARY
            return subProtocol(NotaryProtocol.Client(stx))
        }

        @Suspendable
        private fun receiveAndCheckProposedTransaction(): SignedTransaction {
            progressTracker.currentStep = AWAITING_PROPOSAL

            // Make the first message we'll send to kick off the protocol.
            val hello = SellerTradeInfo(assetToSell, price, myKeyPair.public)

            val maybeSTX = sendAndReceive<SignedTransaction>(otherParty, hello)

            progressTracker.currentStep = VERIFYING

            maybeSTX.unwrap {
                progressTracker.nextStep()

                // Check that the tx proposed by the buyer is valid.
                val wtx: WireTransaction = it.verifySignatures(myKeyPair.public, notaryNode.notaryIdentity.owningKey)
                logger.trace { "Received partially signed transaction: ${it.id}" }

                // Download and check all the things that this transaction depends on and verify it is contract-valid,
                // even though it is missing signatures.
                subProtocol(ResolveTransactionsProtocol(wtx, otherParty))

                if (wtx.outputs.map { it.data }.sumCashBy(myKeyPair.public).withoutIssuer() != price)
                    throw IllegalArgumentException("Transaction is not sending us the right amount of cash")

                // There are all sorts of funny games a malicious secondary might play here, we should fix them:
                //
                // - This tx may attempt to send some assets we aren't intending to sell to the secondary, if
                //   we're reusing keys! So don't reuse keys!
                // - This tx may include output states that impose odd conditions on the movement of the cash,
                //   once we implement state pairing.
                //
                // but the goal of this code is not to be fully secure (yet), but rather, just to find good ways to
                // express protocol state machines on top of the messaging layer.

                return it
            }
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

            logger.trace { "Built finished transaction, sending back to secondary!" }

            send(otherParty, SignaturesFromSeller(ourSignature, notarySignature))
            return fullySigned
        }
    }

    open class Buyer(val otherParty: Party,
                     val notary: Party,
                     val acceptablePrice: Amount<Currency>,
                     val typeToBuy: Class<out OwnableState>) : ProtocolLogic<SignedTransaction>() {

        object RECEIVING : ProgressTracker.Step("Waiting for seller trading info")

        object VERIFYING : ProgressTracker.Step("Verifying seller assets")

        object SIGNING : ProgressTracker.Step("Generating and signing transaction proposal")

        object SWAPPING_SIGNATURES : ProgressTracker.Step("Swapping signatures with the seller")

        override val progressTracker = ProgressTracker(RECEIVING, VERIFYING, SIGNING, SWAPPING_SIGNATURES)

        @Suspendable
        override fun call(): SignedTransaction {
            val tradeRequest = receiveAndValidateTradeRequest()

            progressTracker.currentStep = SIGNING
            val (ptx, cashSigningPubKeys) = assembleSharedTX(tradeRequest)
            val stx = signWithOurKeys(cashSigningPubKeys, ptx)

            val signatures = swapSignaturesWithSeller(stx)

            logger.trace { "Got signatures from seller, verifying ... " }

            val fullySigned = stx + signatures.sellerSig + signatures.notarySig
            fullySigned.verifySignatures()

            logger.trace { "Signatures received are valid. Trade complete! :-)" }
            return fullySigned
        }

        @Suspendable
        private fun receiveAndValidateTradeRequest(): SellerTradeInfo {
            progressTracker.currentStep = RECEIVING
            // Wait for a trade request to come in from the other side
            val maybeTradeRequest = receive<SellerTradeInfo>(otherParty)

            progressTracker.currentStep = VERIFYING
            maybeTradeRequest.unwrap {
                // What is the seller trying to sell us?
                val asset = it.assetForSale.state.data
                val assetTypeName = asset.javaClass.name
                logger.trace { "Got trade request for a $assetTypeName: ${it.assetForSale}" }

                if (it.price > acceptablePrice)
                    throw UnacceptablePriceException(it.price)
                if (!typeToBuy.isInstance(asset))
                    throw AssetMismatchException(typeToBuy.name, assetTypeName)

                // Check the transaction that contains the state which is being resolved.
                // We only have a hash here, so if we don't know it already, we have to ask for it.
                subProtocol(ResolveTransactionsProtocol(setOf(it.assetForSale.ref.txhash), otherParty))

                return it
            }
        }

        @Suspendable
        private fun swapSignaturesWithSeller(stx: SignedTransaction): SignaturesFromSeller {
            progressTracker.currentStep = SWAPPING_SIGNATURES
            logger.trace { "Sending partially signed transaction to seller" }

            // TODO: Protect against the seller terminating here and leaving us in the lurch without the final tx.

            return sendAndReceive<SignaturesFromSeller>(otherParty, stx).unwrap { it }
        }

        private fun signWithOurKeys(cashSigningPubKeys: List<PublicKey>, ptx: TransactionBuilder): SignedTransaction {
            // Now sign the transaction with whatever keys we need to move the cash.
            for (k in cashSigningPubKeys) {
                val priv = serviceHub.keyManagementService.toPrivate(k)
                ptx.signWith(KeyPair(k, priv))
            }

            return ptx.toSignedTransaction(checkSufficientSignatures = false)
        }

        private fun assembleSharedTX(tradeRequest: SellerTradeInfo): Pair<TransactionBuilder, List<PublicKey>> {
            val ptx = TransactionType.General.Builder(notary)
            // Add input and output states for the movement of cash, by using the Cash contract to generate the states.
            val vault = serviceHub.vaultService.currentVault
            val cashStates = vault.statesOfType<Cash.State>()
            val cashSigningPubKeys = Cash().generateSpend(ptx, tradeRequest.price, tradeRequest.sellerOwnerKey, cashStates)
            // Add inputs/outputs/a command for the movement of the asset.
            ptx.addInputState(tradeRequest.assetForSale)
            // Just pick some new public key for now. This won't be linked with our identity in any way, which is what
            // we want for privacy reasons: the key is here ONLY to manage and control ownership, it is not intended to
            // reveal who the owner actually is. The key management service is expected to derive a unique key from some
            // initial seed in order to provide privacy protection.
            val freshKey = serviceHub.keyManagementService.freshKey()
            val (command, state) = tradeRequest.assetForSale.state.data.withNewOwner(freshKey.public)
            ptx.addOutputState(state, tradeRequest.assetForSale.state.notary)
            ptx.addCommand(command, tradeRequest.assetForSale.state.data.owner)

            // And add a request for timestamping: it may be that none of the contracts need this! But it can't hurt
            // to have one.
            val currentTime = serviceHub.clock.instant()
            ptx.setTime(currentTime, 30.seconds)
            return Pair(ptx, cashSigningPubKeys)
        }
    }
}