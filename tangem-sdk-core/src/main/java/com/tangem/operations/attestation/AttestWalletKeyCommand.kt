package com.tangem.operations.attestation

import com.squareup.moshi.JsonClass
import com.tangem.common.CompletionResult
import com.tangem.common.apdu.CommandApdu
import com.tangem.common.apdu.Instruction
import com.tangem.common.apdu.ResponseApdu
import com.tangem.common.card.CardWallet
import com.tangem.common.card.EllipticCurve
import com.tangem.common.card.FirmwareVersion
import com.tangem.common.core.CardSession
import com.tangem.common.core.CompletionCallback
import com.tangem.common.core.SessionEnvironment
import com.tangem.common.core.TangemSdkError
import com.tangem.common.extensions.toByteArray
import com.tangem.common.tlv.TlvBuilder
import com.tangem.common.tlv.TlvDecoder
import com.tangem.common.tlv.TlvTag
import com.tangem.crypto.CryptoUtils
import com.tangem.operations.Command
import com.tangem.operations.CommandResponse

/**
 * Deserialized response from the Tangem card after `AttestWalletKeyCommand`
 *
 * @property cardId          unique Tangem card ID number
 * @property salt            random salt generated by the card
 * @property walletSignature `challenge` and `salt` signed with the wallet private key
 * @property challenge       challenge, used to check wallet
 * @property cardSignature   confirmation signature of the wallet ownership. COS: 2.01+.
 * And a wallet's status for COS: 6.16+.
 * ConfirmationMode.none   : No signature will be returned.
 * ConfirmationMode.static : Wallet's public key and wallet's status (COS 6.16+) signed with the card's private key.
 * ConfirmationMode.dynamic: Wallet's public key, wallet's status (COS 6.16+), `challenge`, and `publicKeySalt`,
 * signed with the card's private key.
 * @property publicKeySalt   optional random salt, generated by the card for `dynamic` `confirmationMode`. COS: 2.01+
 * @property walletStatus   Status of the wallet. COS v.6.16+
 * @property counter         counter of `AttestWalletKey` command executions. A very big value of this counter may
 * indicate a hacking attempts. COS: 2.01+
 */
@Suppress("LongParameterList")
@JsonClass(generateAdapter = true)
class AttestWalletKeyResponse(
    val cardId: String,
    val salt: ByteArray,
    val walletSignature: ByteArray,
    val challenge: ByteArray,
    val cardSignature: ByteArray?,
    val publicKeySalt: ByteArray?,
    val walletStatus: CardWallet.Status?,
    internal val counter: Int?,
) : CommandResponse

/**
 * This command proves that the wallet private key from the card corresponds to the wallet public key.
 * Standard challenge/response scheme is used.
 * @property publicKey: Public key of the wallet to check
 * @property challenge: Optional challenge. If null, it will be created automatically and returned in command response
 * @property confirmationMode: Additional confirmation of the wallet ownership.  The card will return the `cardSignature` (a wallet's public key signed by the card's private key)  in response.  COS: 2.01+.
 */
class AttestWalletKeyCommand(
    private val publicKey: ByteArray,
    challenge: ByteArray? = null,
    private val confirmationMode: ConfirmationMode = ConfirmationMode.Dynamic,
) : Command<AttestWalletKeyResponse>() {

    private val challenge = challenge ?: CryptoUtils.generateRandomBytes(16)

    override fun run(session: CardSession, callback: CompletionCallback<AttestWalletKeyResponse>) {
        super.run(session) { result ->
            when (result) {
                is CompletionResult.Success -> {
                    val checkWalletResponse = result.data
                    val card = session.environment.card
                    val curve = card?.wallet(publicKey)?.curve
                    if (curve == null) {
                        callback(CompletionResult.Failure(TangemSdkError.CardError()))
                        return@run
                    }

                    val isWalletSignatureValid = verifyWalletSignature(response = checkWalletResponse, curve = curve)
                    val isCardSignatureValid = verifyCardSignature(
                        response = checkWalletResponse,
                        cardPublicKey = card.cardPublicKey,
                    )
                    if (isWalletSignatureValid && isCardSignatureValid) {
                        callback(CompletionResult.Success(checkWalletResponse))
                    } else {
                        callback(CompletionResult.Failure(TangemSdkError.CardVerificationFailed()))
                    }
                }
                is CompletionResult.Failure -> callback(result)
            }
        }
    }

    override fun serialize(environment: SessionEnvironment): CommandApdu {
        val card = environment.card ?: throw TangemSdkError.MissingPreflightRead()
        val walletIndex = card.wallet(publicKey)?.index ?: throw TangemSdkError.WalletNotFound()

        val builder = TlvBuilder()
        builder.append(TlvTag.Pin, environment.accessCode.value)
        builder.append(TlvTag.CardId, card.cardId)
        builder.append(TlvTag.Challenge, challenge)
        builder.append(TlvTag.WalletIndex, walletIndex)

        // Otherwise, static confirmation will fail with the "invalidParams" error.
        if (card.firmwareVersion >= FirmwareVersion.WalletOwnershipConfirmationAvailable) {
            when (confirmationMode) {
                ConfirmationMode.None -> {}
                ConfirmationMode.Static -> builder.append(TlvTag.PublicKeyChallenge, byteArrayOf())
                ConfirmationMode.Dynamic -> builder.append(TlvTag.PublicKeyChallenge, challenge)
            }
        }

        return CommandApdu(Instruction.AttestWalletKey, builder.serialize())
    }

    override fun deserialize(environment: SessionEnvironment, apdu: ResponseApdu): AttestWalletKeyResponse {
        val tlv = apdu.getTlvData() ?: throw TangemSdkError.DeserializeApduFailed()
        val decoder = TlvDecoder(tlv)
        return AttestWalletKeyResponse(
            cardId = decoder.decode(TlvTag.CardId),
            salt = decoder.decode(TlvTag.Salt),
            walletSignature = decoder.decode(TlvTag.WalletSignature),
            challenge = challenge,
            cardSignature = decoder.decodeOptional(TlvTag.CardSignature),
            publicKeySalt = decoder.decodeOptional(TlvTag.PublicKeySalt),
            walletStatus = decoder.decodeOptional(TlvTag.Status),
            counter = decoder.decode(TlvTag.CheckWalletCounter),
        )
    }

    private fun verifyWalletSignature(response: AttestWalletKeyResponse, curve: EllipticCurve): Boolean {
        return CryptoUtils.verify(
            publicKey = publicKey,
            message = challenge + response.salt,
            signature = response.walletSignature,
            curve = curve,
        )
    }

    private fun verifyCardSignature(response: AttestWalletKeyResponse, cardPublicKey: ByteArray): Boolean {
        val cardSignature = response.cardSignature ?: return true

        // Dynamic verification
        var message = publicKey
        if (response.publicKeySalt != null) message += challenge + response.publicKeySalt
        if (response.walletStatus != null) message += response.walletStatus.code.toByteArray()

        return CryptoUtils.verify(
            publicKey = cardPublicKey,
            message = message,
            signature = cardSignature,
        )
    }

    enum class ConfirmationMode {
        None,
        Static,
        Dynamic,
    }
}
