package anon.fidoac

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import anon.fidoac.databinding.FragmentScanBinding
import anon.fidoac.service.FIDOACService
import com.google.android.material.tabs.TabLayout
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PACEKeySpec
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

//TODO display information about incoming stuff

// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [ScanFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class ScanFragment : Fragment(), NfcAdapter.ReaderCallback{
    private var param1: String? = null
    private var param2: String? = null

    var passportnumgetter: (()->String)? = null
    var dobgetter: (()->String)? = null
    var expdategetter: (()->String)? = null
    var isDataValid: ((Boolean)->Boolean)? = null
    //Navigate back to when complete
    lateinit var mtab_layout: TabLayout

    private var stateBasket: StateBasket = StateBasket()
    private var mNfcAdapter: NfcAdapter? = null
    private val TAG:String = "scan"

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private lateinit var animatedVectorDrawable:AnimatedVectorDrawableCompat

    fun getPaceKey(documentNumber: String, birthDate: String, expiryDate: String): PACEKeySpec? {
        val bacKeySpec: BACKeySpec = object : BACKeySpec {
            override fun getDocumentNumber(): String {
                return documentNumber!!
            }
            override fun getDateOfBirth(): String {
                return birthDate!!
            }
            override fun getDateOfExpiry(): String {
                return expiryDate!!
            }
            override fun getAlgorithm(): String? {
                return null
            }
            override fun getKey(): ByteArray {
                return ByteArray(0)
            }
        }
        var paceKeySpec: PACEKeySpec? = null
        try {
            Log.i(TAG, bacKeySpec.toString())
            paceKeySpec = PACEKeySpec.createMRZKey(bacKeySpec)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Error initializing MRZKey")
            e.message?.let { Log.e(TAG, it) }
        }
        return paceKeySpec
    }
    fun getBacKey(documentNumber: String, birthDate: String, expiryDate: String): BACKey {
        return BACKey(documentNumber,birthDate, expiryDate)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        val view = binding.root
//        binding.scanbutton.setOnClickListener {
//            if (isDataValid!!(true)){ startScanning() }
//            else{ mtab_layout.getTabAt(1)?.select() }
//        }
        binding.animatedscanBtn.setOnClickListener {
            if (isDataValid!!(true)){
                startScanning()
            }
            else{
                //Data not valid.
                mtab_layout.getTabAt(1)?.select()
            }
        }
        binding.rejectBtnTextview.setOnClickListener {
            stopScanningReject()
            rejectAndSendDataBack()

        }

//        NFC Logic
//        https://stackoverflow.com/questions/64920307/how-to-write-ndef-records-to-nfc-tag/64921434#64921434
        this.mNfcAdapter = NfcAdapter.getDefaultAdapter(this.context);

        return view
    }

    override fun onResume() {
        super.onResume()


        this.binding.textviewOrigin.text = (this.requireActivity() as MainActivity).origin
        this.binding.textviewRequest.text = (this.requireActivity() as MainActivity).request

    }

    override fun onPause() {
        super.onPause()
        stopScanning()
    }

    private var isScanning = false
    private fun startScanning(){
        if (isScanning) return

        isScanning = true
        animatedVectorDrawable = AnimatedVectorDrawableCompat.create(this.requireContext(), R.drawable.avd_anim)!!
        binding.scanImageView.setImageDrawable(animatedVectorDrawable)
        animatedVectorDrawable?.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                binding.scanImageView.post { animatedVectorDrawable.start() }
            }
        })
        if (benchmarkThreshold ==1){
            animatedVectorDrawable?.start()
        }

        val paceKey = getPaceKey(passportnumgetter!!(), dobgetter!!(), expdategetter!!())
        val bacKey = getBacKey(passportnumgetter!!(), dobgetter!!(), expdategetter!!())
        Log.d(TAG,"Passport:"+ passportnumgetter!!() + "\tDOB:" + dobgetter!!() + "\tEXPDATE:"+expdategetter!!())
        this.stateBasket.bacKey = bacKey
        this.stateBasket.paceKey = paceKey
        this.stateBasket.context = this.requireContext()
        this.stateBasket.relyingparty_challenge = (this.requireActivity() as MainActivity).session_server_challenge
        val client_nonce = ByteArray(16)
        SecureRandom.getInstanceStrong().nextBytes(client_nonce)
        this.stateBasket.client_challenge = client_nonce
        if (mNfcAdapter != null) {
            val options = Bundle()
            // Work around for some broken Nfc firmware implementations that poll the card too fast
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            mNfcAdapter!!.enableReaderMode(
                this.requireActivity(),
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NFC_BARCODE,
//                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                options
            )
        }

        binding.instructionInfo.setText(getString(R.string.scanning))
        binding.animatedscanBtn.startAnimation()
    }
    private fun stopScanning(){
        if (!isScanning) return
        //Runned on NON-UI Thread

        if (mNfcAdapter != null) mNfcAdapter!!.disableReaderMode(this.requireActivity()) //Disable after we read the data
        this.requireActivity().runOnUiThread(Runnable {
            binding.instructionInfo.setText(getString(R.string.postscanning))
            binding.animatedscanBtn.doneLoadingAnimation(0, ContextCompat.getDrawable(this.requireContext(), R.drawable.ic_baseline_done_24_white)!!
            .toBitmap())
            binding.scanImageView.setImageDrawable(ContextCompat.getDrawable(this.requireContext(), R.drawable.ic_launcher_foreground_maincolor))
        })
        isScanning = false
    }
    private fun stopScanningReject(){
        binding.instructionInfo.setText("Rejected. Downgrade to FIDO")
//        binding.animatedscanBtn.doneLoadingAnimation(0, ContextCompat.getDrawable(this.requireContext(), R.drawable.ic_baseline_cancel_24)!!.toBitmap())
        binding.scanImageView.setImageDrawable(ContextCompat.getDrawable(this.requireContext(), R.drawable.ic_baseline_cancel_24))

        if (!isScanning) return
        if (mNfcAdapter != null) mNfcAdapter!!.disableReaderMode(this.requireActivity()) //Disable after we read the data
        isScanning = false
    }

    //Send reject information back to browser
    private fun rejectAndSendDataBack(){
        //TODO have a server to send back data to.

        exit()
    }

    private fun sendDataToHTMLServer(stateBasket: StateBasket){
        //We use base64 string here for Json file transfer.
        val mIntent = Intent(this.requireActivity(), FIDOACService::class.java)
        mIntent.putExtra("challenge", Base64.getEncoder().encodeToString(stateBasket.relyingparty_challenge!!))
        mIntent.putExtra("proof", Base64.getEncoder().encodeToString(stateBasket.proof_data!!))
        mIntent.putExtra("hash", Base64.getEncoder().encodeToString(stateBasket.ranomized_hash!!))
        mIntent.putExtra("mediator_sign", Base64.getEncoder().encodeToString(stateBasket.mediator_sign!!) )
        var certBase64Array = ArrayList<String>()
        for (cert in stateBasket.mediator_cert!!){
            certBase64Array.add(Base64.getEncoder().encodeToString(cert))
        }
        mIntent.putStringArrayListExtra("mediator_cert", certBase64Array)
        this.requireActivity().startService(mIntent)

        //TODO have the response back from server and so on, and close the app
    }


    private fun exit(){
        Handler(Looper.getMainLooper()).postDelayed({
            this.requireActivity().finishAndRemoveTask()
        }, 1000)
    }

    // This method is run in another thread when a card is discovered
    // This method cannot cannot direct interact with the UI Thread
    // Use `runOnUiThread` method to change the UI from this method
    var benchmarkCounter = 0
    val benchmarkThreshold = 1
    override fun onTagDiscovered(tag: Tag) {
        // Card should be an IsoDep Technology Type
        var mIsoDep = IsoDep.get(tag)
        mIsoDep.timeout = 5000

        // Check that it is an mIsoDep capable card
        if (mIsoDep != null) {
            Log.d(TAG, "Detected NFC device")
            this.stateBasket.tag = tag
            this.stateBasket.let { it.eidInterface =
                it.tag?.let { it1 -> EIDEPassportInterface(it1, this.stateBasket) }
            }
            val (signature, cert) = this.stateBasket.eidInterface!!.runReadPassportAndMediatorAttestation()
            stateBasket.mediator_sign = signature
            stateBasket.mediator_cert = cert
            Log.d(TAG,"Mediator Completed")
            stopScanning()

            //Compute randomized hash for ExampleVerifier
            val ranomizedHash = MessageDigest.getInstance("SHA-256").digest(stateBasket.sodFile!!.dataGroupHashes[1]!! + stateBasket.client_challenge!!)
            stateBasket.ranomized_hash = ranomizedHash

            Log.d(TAG,"DG1 Raw Byte:" + stateBasket.dg1_raw!!.toHex())
            Log.d(TAG,"DG1 Raw Byte:" + stateBasket.dg1_raw!!.toString(Charsets.US_ASCII))
            Log.d(TAG,"DG1 Raw Byte Length:" + stateBasket.dg1_raw!!.size)
            val age_limit = 20
            stateBasket.proof_data = (this.requireActivity() as MainActivity).snark_sha256(stateBasket.dg1_raw!!, stateBasket.client_challenge!!, age_limit,
                (this.requireActivity() as MainActivity).proving_key, ranomizedHash, (this.requireActivity() as MainActivity).verfication_key)

            //Example verification for reference only. To be called on server side.
            ExampleVerifier.Companion.verify(ranomizedHash, stateBasket.relyingparty_challenge!!, signature, cert,
                stateBasket.proof_data!!,age_limit, (this.requireActivity() as MainActivity).verfication_key)
            Log.d(TAG,"Server Mock Verification (For testing) Completed")

            benchmarkCounter+=1
            if (benchmarkCounter <benchmarkThreshold){
                //Restart Scanning [Benchmark Automation]
                Log.d(TAG,"Loop:" + benchmarkCounter)
                startScanning()
            }

            saveDataToDiskForTesting(this.stateBasket);

            sendDataToHTMLServer(this.stateBasket)
        }

    }

    private fun saveDataToDiskForTesting(stateBasket: StateBasket){
        val sharedPref = this.requireActivity().getSharedPreferences("verf_test_case", Context.MODE_PRIVATE)
        assert(sharedPref !=null)
        sharedPref.edit().putString("challenge", Base64.getEncoder().encodeToString(stateBasket.relyingparty_challenge!!)).commit()
        sharedPref.edit().putString("proof", Base64.getEncoder().encodeToString(stateBasket.proof_data!!)).commit()
        sharedPref.edit().putString("hash", Base64.getEncoder().encodeToString(stateBasket.ranomized_hash!!)).commit()
        sharedPref.edit().putString("mediator_sign", Base64.getEncoder().encodeToString(stateBasket.mediator_sign!!) ).commit()

        var certBase64Array = ArrayList<String>()
        for (cert in stateBasket.mediator_cert!!){
            certBase64Array.add(Base64.getEncoder().encodeToString(cert))
        }
        var counter=0
        for (cert in certBase64Array) {
            sharedPref.edit().putString("mediator_cert_"+counter, cert ).commit()
            counter += 1
        }

    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment ScanFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            ScanFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}