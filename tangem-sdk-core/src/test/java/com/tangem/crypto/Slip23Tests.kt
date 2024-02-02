package com.tangem.crypto

import com.tangem.common.extensions.toHexString
import com.tangem.crypto.bip39.BIP39Wordlist
import com.tangem.crypto.bip39.BIP39WordlistTest
import com.tangem.crypto.bip39.DefaultMnemonic
import com.tangem.crypto.bip39.Wordlist
import com.tangem.crypto.hdWallet.Slip23
import org.junit.Test
import java.io.InputStream
import kotlin.test.assertEquals

internal class Slip23Tests {

    init {
        CryptoUtils.initCrypto()
    }

    @Test
    fun testIkarus() {
        val expectedKl = "08c1d64cdce875122012d1d81611e83ebf0823b2c6df97a99c55ee35ef5b5547"
        val expectedKr = "3916ba9c8add605b1bb4db40bb7ae4049089051250a48479795a7e63d23d4cde"
        val expectedPublicKey = "32ea4ee339b0b01233e5f0728d733dc68a26d17a58c140aa23fe1c8eeabd5abe"
        val expectedChainCode = "055d207e832382121b9ff6c339628368131f90f9a50a3e36ffbbcba804fbc4dc"

        val mnemonic = DefaultMnemonic(
            "tiny escape drive pupil flavor endless love walk gadget match filter luxury",
            createDefaultWordlist(),
        )
        val entropy = mnemonic.getEntropy()

        val privateKey = Slip23().makeIkarusMasterKey(entropy, "")

        assertEquals(privateKey.privateKey.take(32).toByteArray().toHexString().lowercase(), expectedKl)
        assertEquals(privateKey.privateKey.takeLast(32).toByteArray().toHexString().lowercase(), expectedKr)
        assertEquals(privateKey.chainCode.toHexString().lowercase(), expectedChainCode)

        // generated by card
        val publicKeyFromCard = "32EA4EE339B0B01233E5F0728D733DC68A26D17A58C140AA23FE1C8EEABD5ABE"
        assertEquals(publicKeyFromCard.lowercase(), expectedPublicKey)
    }

    @Test
    fun testNoPassphrase() {
        val expectedPrivateKey = "c065afd2832cd8b087c4d9ab7011f481ee1e0721e78ea5dd609f3ab3f156d245d176bd8fd4ec60b473" +
            "1c3918a2a72a0226c0cd119ec35b47e4d55884667f552a23f7fdcd4a10c6cd2c7393ac61d877873e248f417634aa3d812af327f" +
            "fe9d620"
        val mnemonic = DefaultMnemonic(
            "eight country switch draw meat scout mystery blade tip drift useless good keep usage title",
            createDefaultWordlist(),
        )
        val entropy = mnemonic.getEntropy()
        val privateKey = Slip23().makeIkarusMasterKey(entropy, "")
        val concatenated = privateKey.privateKey + privateKey.chainCode
        assertEquals(concatenated.toHexString().lowercase(), expectedPrivateKey)
    }

    @Test
    fun testWithPassphrase() {
        val expectedPrivateKey = "70531039904019351e1afb361cd1b312a4d0565d4ff9f8062d38acf4b15cce41d7b5738d9c893feea5" +
            "5512a3004acb0d222c35d3e3d5cde943a15a9824cbac59443cf67e589614076ba01e354b1a432e0e6db3b59e37fc56b5fb02229" +
            "70a010e"
        val mnemonic = DefaultMnemonic(
            "eight country switch draw meat scout mystery blade tip drift useless good keep usage title",
            createDefaultWordlist(),
        )
        val entropy = mnemonic.getEntropy()
        val privateKey = Slip23().makeIkarusMasterKey(entropy, "foo")
        val concatenated = privateKey.privateKey + privateKey.chainCode
        assertEquals(concatenated.toHexString().lowercase(), expectedPrivateKey)
    }

    private fun createDefaultWordlist(): Wordlist {
        val wordlistStream = getInputStreamForTestFile()
        return BIP39Wordlist(wordlistStream)
    }

    private fun getInputStreamForTestFile(): InputStream {
        return object {}.javaClass.classLoader.getResourceAsStream(BIP39WordlistTest.TEST_DICTIONARY_FILE_NAME)!!
    }
}
