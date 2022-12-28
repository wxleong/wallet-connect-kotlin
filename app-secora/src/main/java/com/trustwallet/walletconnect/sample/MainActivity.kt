package com.trustwallet.walletconnect.sample

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.infineon.ByteUtils
import com.github.infineon.NfcUtils
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.GsonBuilder
import com.trustwallet.walletconnect.WCClient
import com.trustwallet.walletconnect.exceptions.InvalidSessionException
import com.trustwallet.walletconnect.models.WCAccount
import com.trustwallet.walletconnect.models.WCPeerMeta
import com.trustwallet.walletconnect.models.WCSignTransaction
import com.trustwallet.walletconnect.models.binance.WCBinanceCancelOrder
import com.trustwallet.walletconnect.models.binance.WCBinanceTradeOrder
import com.trustwallet.walletconnect.models.binance.WCBinanceTransferOrder
import com.trustwallet.walletconnect.models.binance.WCBinanceTxConfirmParam
import com.trustwallet.walletconnect.models.ethereum.WCEthereumSignMessage
import com.trustwallet.walletconnect.models.ethereum.WCEthereumTransaction
import com.trustwallet.walletconnect.models.session.WCSession
import com.trustwallet.walletconnect.sample.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import wallet.core.jni.CoinType
import wallet.core.jni.PublicKey
import wallet.core.jni.PublicKeyType


class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener {

    /* https://beakutis.medium.com/using-googles-mlkit-and-camerax-for-lightweight-barcode-scanning-bb2038164cdc */

    /* The order of this enum has to be consistent with the
       nfc_actions string-array from strings.xml */
    enum class Actions {
        READ_OR_CREATE_KEYPAIR, GEN_KEYPAIR_FROM_SEED, SIGN_MESSAGE,
        SET_PIN, CHANGE_PIN, VERIFY_PIN, UNLOCK_PIN
    }

    private lateinit var binding: ActivityMainBinding
    private var action: Actions = Actions.READ_OR_CREATE_KEYPAIR
    private var nfcAdapter: NfcAdapter? = null
    private var nfcCallback: ((IsoTagWrapper)->Unit) =
        { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }
    private var pendingIntent: PendingIntent? = null

    private val wcClient by lazy {
        WCClient(GsonBuilder(), OkHttpClient())
    }

    private var address = ""

    private val peerMeta = WCPeerMeta(name = "Example", url = "https://example.com")

    private lateinit var wcSession: WCSession

    private var remotePeerMeta: WCPeerMeta? = null

    companion object {
        init {
            System.loadLibrary("TrustWalletCore")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val spinner: Spinner = findViewById(R.id.nfc_spinner)
        spinner.onItemSelectedListener = this
        ArrayAdapter.createFromResource(
            this,
            R.array.nfc_actions,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }
        action = Actions.READ_OR_CREATE_KEYPAIR
        findViewById<TextInputLayout?>(R.id.nfc_keyhandle).visibility = View.VISIBLE

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC functionality not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, this.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        wcClient.onDisconnect = { _, _ -> onDisconnect() }
        wcClient.onFailure = { t -> onFailure(t) }
        wcClient.onSessionRequest = { _, peer -> onSessionRequest(peer) }
        wcClient.onGetAccounts = { id -> onGetAccounts(id) }

        wcClient.onEthSign = { id, message -> onEthSign(id, message) }
        wcClient.onEthSignTransaction = { id, transaction -> onEthTransaction(id, transaction) }
        wcClient.onEthSendTransaction = { id, transaction -> onEthTransaction(id, transaction, send = true) }

        wcClient.onBnbTrade = { id, order -> onBnbTrade(id, order) }
        wcClient.onBnbCancel = { id, order -> onBnbCancel(id, order) }
        wcClient.onBnbTransfer = { id, order -> onBnbTransfer(id, order) }
        wcClient.onBnbTxConfirm = { _, param -> onBnbTxConfirm(param) }
        wcClient.onSignTransaction = { id, transaction -> onSignTransaction(id, transaction) }

        setupConnectButton()
    }

    private fun setupConnectButton() {
        runOnUiThread {
            binding.connectButton.text = "Connect"
            binding.connectButton.setOnClickListener {
                connect(binding.uriInput.editText?.text?.toString() ?: return@setOnClickListener)
            }
        }
    }

    fun connect(uri: String) {
        disconnect()
        wcSession = WCSession.from(uri) ?: throw InvalidSessionException()
        wcClient.connect(wcSession, peerMeta)
    }

    fun disconnect() {
        if (wcClient.session != null) {
            wcClient.killSession()
        } else {
            wcClient.disconnect()
        }
    }

    fun approveSession() {
        val address = binding.addressInput.editText?.text?.toString() ?: address
        val chainId = binding.chainInput.editText?.text?.toString()?.toIntOrNull() ?: 1
        wcClient.approveSession(listOf(address), chainId)
        binding.connectButton.text = "Kill Session"
        binding.connectButton.setOnClickListener {
            disconnect()
        }
    }

    fun rejectSession() {
        wcClient.rejectSession()
        wcClient.disconnect()
    }

    fun rejectRequest(id: Long) {
        wcClient.rejectRequest(id, "User canceled")
    }

    private fun onDisconnect() {
        setupConnectButton()
    }

    private fun onFailure(throwable: Throwable) {
        throwable.printStackTrace()
    }

    private fun onSessionRequest(peer: WCPeerMeta) {
        runOnUiThread {
            remotePeerMeta = peer
            wcClient.remotePeerId ?: run {
                println("remotePeerId can't be null")
                return@runOnUiThread
            }
            val meta = remotePeerMeta ?: return@runOnUiThread
            val alertDialog = AlertDialog.Builder(this)
                .setTitle(meta.name)
                .setMessage("${meta.description}\n${meta.url}")
                .setPositiveButton("Tap Your Card To Approve") { dialog, _ ->
                    setNfcCallback { isoTagWrapper ->
                        val pubkey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                            isoTagWrapper, 1
                        ).publicKey
                        address = CoinType.ETHEREUM.deriveAddressFromPublicKey(
                            PublicKey(
                                pubkey,
                                PublicKeyType.SECP256K1
                            )
                        )
                        approveSession()
                        dialog.dismiss()
                    }
                }
                .setNegativeButton("Reject") { _, _ ->
                    rejectSession()
                }
                .show()

            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }
    }

    private fun onEthSign(id: Long, message: WCEthereumSignMessage) {
        runOnUiThread {
            val alertDialog = AlertDialog.Builder(this)
                .setTitle(message.type.name)
                .setMessage(message.data)
                .setPositiveButton("Tap Your Card To Sign") { dialog, _ ->
                    setNfcCallback { isoTagWrapper ->
                        val signature = NfcUtils.generateSignature(
                            isoTagWrapper, 1, message.data.decodeHex(), null
                        )
                        wcClient.approveRequest(id, signature.data)
                        dialog.dismiss()
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    rejectRequest(id)
                }
                .show()

            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }
    }

    private fun onEthTransaction(id: Long, payload: WCEthereumTransaction, send: Boolean = false) { }

    private fun onBnbTrade(id: Long, order: WCBinanceTradeOrder) { }

    private fun onBnbCancel(id: Long, order: WCBinanceCancelOrder) { }

    private fun onBnbTransfer(id: Long, order: WCBinanceTransferOrder) { }

    private fun onBnbTxConfirm(param: WCBinanceTxConfirmParam) { }

    private fun onGetAccounts(id: Long) {
        val account = WCAccount(
            binding.chainInput.editText?.text?.toString()?.toIntOrNull() ?: 1,
            binding.addressInput.editText?.text?.toString() ?: address,
        )
        wcClient.approveRequest(id, account)
    }

    private fun onSignTransaction(id: Long, payload: WCSignTransaction) { }

    fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return removePrefix("0x")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val tag: Tag? = intent!!.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val isoDep = IsoDep.get(tag) /* ISO 14443-4 Type A & B */

        nfcCallback?.let { it(IsoTagWrapper(isoDep)) }

        isoDep.close()
    }

    private fun setNfcCallback(f: (isoTagWrapper: IsoTagWrapper)->Unit) {
        nfcCallback = f.also {
            setNfcCallback { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }
        }
    }

    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
        /* An item was selected. You can retrieve the selected item using
           parent.getItemAtPosition(pos) */
        action = Actions.values()[pos]

        val nfc_keyhandle: TextInputLayout = binding.nfcKeyhandle
        val nfc_pin_use: TextInputLayout = binding.nfcPinUse
        val nfc_pin_set: TextInputLayout = binding.nfcPinSet
        val nfc_pin_verify: TextInputLayout = binding.nfcPinVerify
        val nfc_puk: TextInputLayout = binding.nfcPuk
        val nfc_pin_cur: TextInputLayout = binding.nfcPinCur
        val nfc_pin_new: TextInputLayout = binding.nfcPinNew
        val nfc_seed: TextInputLayout = binding.nfcSeed
        val nfc_message: TextInputLayout = binding.nfcMessage

        nfc_keyhandle.visibility = View.GONE
        nfc_keyhandle.editText?.inputType = InputType.TYPE_CLASS_NUMBER
        nfc_pin_use.visibility = View.GONE
        nfc_pin_set.visibility = View.GONE
        nfc_pin_verify.visibility = View.GONE
        nfc_puk.visibility = View.GONE
        nfc_puk.editText?.inputType = InputType.TYPE_CLASS_TEXT
        nfc_pin_cur.visibility = View.GONE
        nfc_pin_new.visibility = View.GONE
        nfc_seed.visibility = View.GONE
        nfc_message.visibility = View.GONE

        if (nfc_keyhandle.editText?.text.toString() == "")
            nfc_keyhandle.editText?.setText("1")
        if (nfc_pin_use.editText?.text.toString() == "")
            nfc_pin_use.editText?.setText("0")
        if (nfc_pin_set.editText?.text.toString() == "")
            nfc_pin_set.editText?.setText("0000")
        if (nfc_pin_verify.editText?.text.toString() == "")
            nfc_pin_verify.editText?.setText("0000")
        if (nfc_puk.editText?.text.toString() == "")
            nfc_puk.editText?.setText("0000000000000000")
        if (nfc_pin_cur.editText?.text.toString() == "")
            nfc_pin_cur.editText?.setText("0000")
        if (nfc_pin_new.editText?.text.toString() == "")
            nfc_pin_new.editText?.setText("0000")
        if (nfc_seed.editText?.text.toString() == "")
            nfc_seed.editText?.setText("0123456789ABCDEF0123456789ABCDEF")
        if (nfc_message.editText?.text.toString() == "")
            nfc_message.editText?.setText("Some messages")

        when (action) {
            Actions.READ_OR_CREATE_KEYPAIR -> {
                nfc_keyhandle.visibility = View.VISIBLE
            }
            Actions.GEN_KEYPAIR_FROM_SEED -> {
                nfc_keyhandle.visibility = View.VISIBLE
                nfc_keyhandle.editText?.inputType = View.AUTOFILL_TYPE_NONE
                nfc_keyhandle.editText?.setText("0")
                nfc_seed.visibility = View.VISIBLE
                nfc_pin_use.visibility = View.VISIBLE
            }
            Actions.SIGN_MESSAGE -> {
                nfc_keyhandle.visibility = View.VISIBLE
                nfc_pin_use.visibility = View.VISIBLE
                nfc_message.visibility =View.VISIBLE
            }
            Actions.SET_PIN -> {
                nfc_pin_set.visibility = View.VISIBLE
                nfc_puk.visibility = View.VISIBLE
                nfc_puk.editText?.inputType = InputType.TYPE_NULL
                nfc_puk.editText?.setTextIsSelectable(true)
            }
            Actions.CHANGE_PIN -> {
                nfc_pin_cur.visibility = View.VISIBLE
                nfc_pin_new.visibility = View.VISIBLE
                nfc_puk.visibility = View.VISIBLE
                nfc_puk.editText?.inputType = InputType.TYPE_NULL
                nfc_puk.editText?.setTextIsSelectable(true)
            }
            Actions.VERIFY_PIN -> {
                nfc_pin_verify.visibility = View.VISIBLE
            }
            Actions.UNLOCK_PIN -> {
                nfc_puk.visibility = View.VISIBLE
            }
            else -> {

            }
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>) { }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null) {
            if (!nfcAdapter!!.isEnabled()) {
                openNfcSettings();
            }
            nfcAdapter!!.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    private fun openNfcSettings() {
        Toast.makeText(this, "Please enable NFC", Toast.LENGTH_SHORT).show()
        val intent = Intent(Settings.ACTION_NFC_SETTINGS)
        startActivity(intent)
    }

    private fun nfcDefaultCallback(isoTagWrapper: IsoTagWrapper) {

        try {
            val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
            val pin_use = binding.nfcPinUse.editText?.text.toString()
            val pin_set = binding.nfcPinSet.editText?.text.toString()
            val pin_cur = binding.nfcPinCur.editText?.text.toString()
            val pin_new = binding.nfcPinNew.editText?.text.toString()
            val pin_verify = binding.nfcPinVerify.editText?.text.toString()
            val puk = binding.nfcPuk.editText?.text.toString()
            val seed = binding.nfcSeed.editText?.text.toString()
            val message = binding.nfcMessage.editText?.text.toString()
            var ret: Boolean = false

            when (action) {
                Actions.READ_OR_CREATE_KEYPAIR -> {
                    val pubkey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                        isoTagWrapper, Integer.parseInt(keyHandle)
                    )

                    address = CoinType.ETHEREUM.deriveAddressFromPublicKey(
                        PublicKey(
                            pubkey.publicKey,
                            PublicKeyType.SECP256K1EXTENDED
                        )
                    )

                    binding.addressInput.editText?.setText(address)

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "Address:\n$address\n\n" +
                                    "Signature counter:\n${Integer.decode("0x" + ByteUtils.bytesToHex(pubkey.sigCounter))}\n\n" +
                                    "Global signature counter:\n${Integer.decode("0x" + ByteUtils.bytesToHex(pubkey.globalSigCounter))}"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.GEN_KEYPAIR_FROM_SEED -> {

                    /* Generate keypair from seed */

                    if (pin_use == "0")
                        ret = NfcUtils.generateKeyFromSeed(isoTagWrapper, seed.decodeHex(), null)
                    else
                        ret = NfcUtils.generateKeyFromSeed(isoTagWrapper, seed.decodeHex(), pin_use.decodeHex())

                    if (!ret)
                        throw Exception("Invalid PIN")

                    /* Read back and display the key info */

                    val pubkey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                        isoTagWrapper, 0
                    )

                    address = CoinType.ETHEREUM.deriveAddressFromPublicKey(
                        PublicKey(
                            pubkey.publicKey,
                            PublicKeyType.SECP256K1EXTENDED
                        )
                    )

                    binding.addressInput.editText?.setText(address)

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "Address:\n$address\n\n" +
                                    "Signature counter:\n${Integer.decode("0x" + ByteUtils.bytesToHex(pubkey.sigCounter))}\n\n" +
                                    "Global signature counter:\n${Integer.decode("0x" + ByteUtils.bytesToHex(pubkey.globalSigCounter))}"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.SIGN_MESSAGE -> {

                }
                Actions.SET_PIN -> {

                }
                Actions.CHANGE_PIN -> {

                }
                Actions.VERIFY_PIN -> {

                }
                Actions.UNLOCK_PIN -> {

                }
                else -> {

                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }
}
