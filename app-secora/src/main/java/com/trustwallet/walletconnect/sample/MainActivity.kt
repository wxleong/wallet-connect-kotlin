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
import com.budiyev.android.codescanner.AutoFocusMode
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.budiyev.android.codescanner.ScanMode
import com.github.infineon.NfcUtils
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.GsonBuilder
import com.trustwallet.walletconnect.WCClient
import com.trustwallet.walletconnect.exceptions.InvalidSessionException
import com.trustwallet.walletconnect.extensions.toHex
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
import okhttp3.internal.and
import wallet.core.jni.CoinType
import wallet.core.jni.PublicKey
import wallet.core.jni.PublicKeyType

class MainActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener {

    /* The order of this enum has to be consistent with the
       nfc_actions string-array from strings.xml */
    enum class Actions {
        READ_OR_CREATE_KEYPAIR, GEN_KEYPAIR_FROM_SEED, SIGN_MESSAGE,
        SET_PIN, CHANGE_PIN, VERIFY_PIN, UNLOCK_PIN
    }

    private lateinit var binding: ActivityMainBinding
    private var action: Actions = Actions.READ_OR_CREATE_KEYPAIR
    private var nfcCallback: ((IsoTagWrapper)->Unit) =
        { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private lateinit var codeScanner: CodeScanner

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

        /* Set up NFC */

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

        /* Set up QR scanner */

        codeScanner = CodeScanner(this, binding.scannerView)
        codeScanner.camera = CodeScanner.CAMERA_BACK
        codeScanner.formats = CodeScanner.ALL_FORMATS
        codeScanner.autoFocusMode = AutoFocusMode.SAFE
        codeScanner.scanMode = ScanMode.SINGLE
        codeScanner.isAutoFocusEnabled = true // Whether to enable auto focus or not
        codeScanner.isFlashEnabled = false // Whether to enable flash or not

        codeScanner.decodeCallback = DecodeCallback {
            runOnUiThread {
                codeScanner.releaseResources()
                binding.scannerFrame.visibility = View.GONE
                binding.uriInput.editText?.setText(it.text)
                Toast.makeText(this, "Scan result: ${it.text}", Toast.LENGTH_LONG).show()
            }
        }
        codeScanner.errorCallback = ErrorCallback {
            runOnUiThread {
                codeScanner.releaseResources()
                binding.scannerFrame.visibility = View.GONE
                Toast.makeText(this, "Camera initialization error: ${it.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
        binding.scannerButton.setOnClickListener {
            if (binding.scannerFrame.visibility == View.GONE) {
                codeScanner.startPreview()
                binding.scannerFrame.visibility = View.VISIBLE
            } else {
                codeScanner.releaseResources()
                binding.scannerFrame.visibility = View.GONE
            }
        }

        /* Set up wallet connect */

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
            AlertDialog.Builder(this)
                .setTitle(meta.name)
                .setMessage("${meta.description}\n${meta.url}")
                .setPositiveButton("Approve") { _, _ ->
                    approveSession()
                }
                .setNegativeButton("Reject") { _, _ ->
                    rejectSession()
                }
                .show()
        }
    }

    private fun onEthSign(id: Long, message: WCEthereumSignMessage) {
        runOnUiThread {
            val alertDialog = AlertDialog.Builder(this)
                .setTitle(message.type.name)
                .setMessage(message.data)
                .setPositiveButton("Tap Your Card To Sign") { _, _ ->
                }
                .setNegativeButton("Cancel") { _, _ ->
                    rejectRequest(id)
                }
                .show()

            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

            nfcCallback = { isoTagWrapper ->
                val keyHandle = binding.nfcKeyhandle.editText?.text.toString()
                val pin_use = binding.nfcPinUse.editText?.text.toString()
                var pin: ByteArray? = null

                if (pin_use != "0")
                    pin = pin_use.decodeHex()

                val signature = NfcUtils.generateSignature(
                    isoTagWrapper, Integer.parseInt(keyHandle), message.data.decodeHex(), pin
                )
                var asn1Signature = signature.signature.toHex()
                asn1Signature = asn1Signature.substring(0, asn1Signature.length - 4)

                /* Signature redundancy check */

                val rawPublicKey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                    isoTagWrapper, Integer.parseInt(keyHandle)
                )
                val publicKey = PublicKey(
                    rawPublicKey.publicKey,
                    PublicKeyType.SECP256K1EXTENDED
                )
                if (!publicKey.verifyAsDER(asn1Signature.decodeHex(), message.data.decodeHex())) {
                    throw Exception("Signature verification failed")
                }
                val r = extractR(asn1Signature.decodeHex())
                val s = verifyAndExtractS(asn1Signature.decodeHex())
                if (!publicKey.verify(r + s, message.data.decodeHex())) {
                    throw Exception("Signature verification failed")
                }
                val rs = r + s

                /* Determine the component v */

                val v = byteArrayOf(0)

                for (i in 0..3) {
                    val recoveredPublicKey = PublicKey.recover(rs + v, message.data.decodeHex())

                    if (recoveredPublicKey != null
                        && recoveredPublicKey.data() contentEquals publicKey.data())
                        break
                    v[0]++
                }

                val rsv = rs + v

                wcClient.approveRequest(id, rsv)
                alertDialog.dismiss()
            }
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
        try {
            val tag: Tag? = intent!!.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            val isoDep = IsoDep.get(tag) /* ISO 14443-4 Type A & B */

            nfcCallback?.let { it(IsoTagWrapper(isoDep)) }

            isoDep.close()
        } catch (e: Exception) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
        } finally {
            nfcCallback = { isoTagWrapper -> nfcDefaultCallback(isoTagWrapper) }
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
            nfc_pin_set.editText?.setText("00000000")
        if (nfc_pin_verify.editText?.text.toString() == "")
            nfc_pin_verify.editText?.setText("00000000")
        if (nfc_puk.editText?.text.toString() == "")
            nfc_puk.editText?.setText("0000000000000000")
        if (nfc_pin_cur.editText?.text.toString() == "")
            nfc_pin_cur.editText?.setText("00000000")
        if (nfc_pin_new.editText?.text.toString() == "")
            nfc_pin_new.editText?.setText("00000000")
        if (nfc_seed.editText?.text.toString() == "")
            nfc_seed.editText?.setText("00112233445566778899AABBCCDDEEFF")
        if (nfc_message.editText?.text.toString() == "")
            nfc_message.editText?.setText("00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF")

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

    override fun onPause() {
        codeScanner.releaseResources()
        binding.scannerFrame.visibility = View.GONE
        super.onPause()
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
                            "Signature counter:\n${Integer.decode("0x" + pubkey.sigCounter.toHex())}\n\n" +
                            "Global signature counter:\n${Integer.decode("0x" + pubkey.globalSigCounter.toHex())}"
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
                            "Signature counter:\n${Integer.decode("0x" + pubkey.sigCounter.toHex())}\n\n" +
                            "Global signature counter:\n${Integer.decode("0x" + pubkey.globalSigCounter.toHex())}"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.SIGN_MESSAGE -> {
                    var pin: ByteArray? = null

                    if (pin_use != "0")
                        pin = pin_use.decodeHex()

                    val signature = NfcUtils.generateSignature(isoTagWrapper, Integer.parseInt(keyHandle), message.decodeHex(), pin)
                    var asn1Signature = signature.signature.toHex()
                    asn1Signature = asn1Signature.substring(0, asn1Signature.length - 4)

                    /* ASN.1 DER encoded signature
                       Example:
                           3045022100D962A9F5185971A1229300E8FC7E699027F90843FBAD5DE060
                           CA4B289CF88D580220222BAB7E5BCC581373135A5E8C9B1933398B994814
                           CE809FA1053F5E17BC1733
                       Breakdown:
                           30: DER TAG Signature
                           45: Total length of signature
                           02: DER TAG component
                           21: Length of R
                           00D962A9F5185971A1229300E8FC7E699027F90843FBAD5DE060CA4B289CF88D58
                           02: DER TAG component
                           20: Length of S
                           222BAB7E5BCC581373135A5E8C9B1933398B994814CE809FA1053F5E17BC1733
                     */

                    /* Signature redundancy check */

                    val rawPublicKey = NfcUtils.readPublicKeyOrCreateIfNotExists(
                        isoTagWrapper, Integer.parseInt(keyHandle)
                    )
                    val publicKey = PublicKey(
                        rawPublicKey.publicKey,
                        PublicKeyType.SECP256K1EXTENDED
                    )
                    if (!publicKey.verifyAsDER(asn1Signature.decodeHex(), message.decodeHex())) {
                        throw Exception("Signature verification failed")
                    }
                    val r = extractR(asn1Signature.decodeHex())
                    val s = verifyAndExtractS(asn1Signature.decodeHex())
                    if (!publicKey.verify(r + s, message.decodeHex())) {
                        throw Exception("Signature verification failed")
                    }

                    /* Determine the component v */

                    val rs = r + s
                    val v = byteArrayOf(0)

                    for (i in 0..3) {
                        val recoveredPublicKey = PublicKey.recover(rs + v, message.decodeHex())

                        if (recoveredPublicKey != null
                            && recoveredPublicKey.data() contentEquals publicKey.data())
                            break
                        v[0]++
                    }

                    val rsv = rs + v

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "Signature (ASN.1):\n${rsv.toHex()}\n\n" +
                            "r:\n${r.toHex()}\n\n" +
                            "s:\n${s.toHex()}\n\n" +
                            "v:\n${v.toHex()}\n\n" +
                            "Signature counter:\n${Integer.decode("0x" + signature.sigCounter.toHex())}\n\n" +
                            "Global signature counter:\n${Integer.decode("0x" + signature.globalSigCounter.toHex())}"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.SET_PIN -> {
                    val puk = NfcUtils.initializePinAndReturnPuk(isoTagWrapper, pin_set.decodeHex())
                    binding.nfcPuk.editText?.setText(puk.toHex())

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "Remember the PUK:\n${puk.toHex()}\n\n" +
                            "and the PIN:\n${pin_set}"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.CHANGE_PIN -> {
                    val puk = NfcUtils.changePin(isoTagWrapper, pin_cur.decodeHex(), pin_new.decodeHex())
                    binding.nfcPuk.editText?.setText(puk.toHex())

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "Remember the PUK:\n${puk.toHex()}\n\n" +
                            "and the PIN:\n${pin_new}"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.VERIFY_PIN -> {
                    /* A bug in com.github.infineon:secora-blockchain-apdu:1.0.0
                       missing selectApplication(card) before the verifyPin()
                       TO BE FIXED */
                    if (!NfcUtils.verifyPin(isoTagWrapper, pin_verify.decodeHex()))
                        throw Exception("Invalid PIN")

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "PIN verification passed"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                Actions.UNLOCK_PIN -> {
                    if (!NfcUtils.unlockPin(isoTagWrapper, puk.decodeHex()))
                        throw Exception("Invalid PUK")

                    AlertDialog.Builder(this)
                        .setTitle("Response")
                        .setMessage(
                            "PIN Unlocked, remember to set a new PIN"
                        )
                        .setPositiveButton("Dismiss") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                else -> {

                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun extractR(signature: ByteArray): ByteArray {
        val startR = if ((signature[1] and 0x80) != 0) 3 else 2
        val lengthR = signature[startR + 1]
        var skipZeros = 0
        var addZeros = ByteArray(0)

        if (lengthR > 32)
            skipZeros = lengthR - 32
        if (lengthR < 32)
            addZeros = ByteArray(32 - lengthR)

        return addZeros + signature.copyOfRange(startR + 2 + skipZeros, startR + 2 + lengthR)
    }

    private fun verifyAndExtractS(signature: ByteArray): ByteArray {
        val startR = if ((signature[1] and 0x80) != 0) 3 else 2
        val lengthR = signature[startR + 1]
        val startS = startR +2 + lengthR;
        val lengthS = signature [startS + 1];
        var skipZeros = 0
        var addZeros = ByteArray(0)

        if (lengthS > 32)
            skipZeros = lengthS - 32
        if (lengthS < 32)
            addZeros = ByteArray(32 - lengthS)

        val s: ByteArray = addZeros + signature.copyOfRange(startS + 2 + skipZeros, startS + 2 + lengthS)

        if (s[0] >= 0x80)
            throw Exception("Signature is vulnerable to malleability attack")

        return s
    }
}
