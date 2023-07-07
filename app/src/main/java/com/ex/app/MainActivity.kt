package com.ex.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaDrm.ErrorCodes
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.ex.app.databinding.ActivityMainBinding
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private var blockMillis = 30000L
    private var blockTime = 0L
    private var isBiometricBlocked: Boolean = false

    /**
     * [1].BiometricReturnType.TRUE : 생체 인증 가능한 경우
     * [2].BiometricReturnType.FALSE : 디바이스에 적절한 인식 센서가 없는 경우
     * [3].BiometricReturnType.EMPTY : 생체 인식 정보가 등록되어 있지 않은 경우
     * [4].BiometricReturnType.EXCEPTION : 지문 인증을 일시적으로 사용할 수 없거나 보안 업데이트가 필요한 경우
     */
    private enum class BiometricReturnType {
        SUCCESS, FAIL, EXCEPTION, EMPTY
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.main = this@MainActivity

        checkDeviceAndUpdateUi()
    }

    private fun checkDeviceAndUpdateUi() {
        when(isPossibleToUseBiometric()) {
            BiometricReturnType.EXCEPTION -> { //버튼 비활성화
                Toast.makeText(this, "지문 인증을 사용할 수 없거나 보안 업데이트가 필요합니다.", Toast.LENGTH_SHORT).show()
                binding.isVisibleBiometricUI = true
            }
            else -> {}
        }
    }

    //생체 인증이 가능한지 확인한다.
    private fun isPossibleToUseBiometric(): BiometricReturnType {
        val canAuthenticate = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        when (canAuthenticate) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Timber.d("BIOMETRIC_SUCCESS")
                return BiometricReturnType.SUCCESS
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> { //생체 인식 정보가 등록되어 있지 않은 경우
                Timber.d("BIOMETRIC_ERROR_NONE_ENROLLED")
                return BiometricReturnType.EMPTY
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> { //디바이스에 적절한 센서가 없는 경우
                Timber.d("BIOMETRIC_ERROR_NO_HARDWARE")
                return BiometricReturnType.FAIL
            }
            else -> { //지문 인증을 사용할 수 없거나 보안 업데이트가 필요한 경우
                Timber.d("BIOMETRIC_ERROR_HW_UNAVAILABLE or BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED")
                return BiometricReturnType.EXCEPTION
            }
        }
    }

    //지문 인식이 가능한 경우, 지문 인식 프롬프트를 띄운다.
    private fun showBiometricPrompt(activity: AppCompatActivity) {
        if (isBiometricBlocked) {
            Timber.d("임시 블럭 상태 // 블럭시간 $blockTime //블럭 해제 시간 ${blockTime + blockMillis} //현재 시간 : ${System.currentTimeMillis()}")
            if (System.currentTimeMillis() < (blockTime + blockMillis)) { //블럭 상태
                Toast.makeText(this@MainActivity, "시도 횟수가 너무 많습니다. 나중에 다시 시도하세요.", Toast.LENGTH_SHORT).show()
                return
            } else {
                isBiometricBlocked = true
                Timber.d("블럭 해제!!!!")
            }
        }

        val promptUi = BiometricPrompt.PromptInfo.Builder().apply {
            setTitle(getString(R.string.prompt_title))
            setSubtitle(getString(R.string.prompt_subtitle))
            setDescription(getString(R.string.prompt_description))
            setNegativeButtonText(getString(R.string.prompt_negative_button))
            setConfirmationRequired(false)
        }.build()

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errCode: Int, errString: CharSequence) { //지문 인식 ERROR
                super.onAuthenticationError(errCode, errString)
                Timber.e("errCode is $errCode and errString is: $errString")
                when(errCode) {
                    7 -> { //시도 횟수가 너무 많습니다. 나중에 다시 시도하세요. -> 30 초 블럭!
                        blockTime = System.currentTimeMillis()
                        isBiometricBlocked = true //생체 인식 30초 임시 블럭
                    }
                    9 -> { //시도 횟수가 너무 많습니다. 지문 센서가 사용 중지되었습니다. -> 제법 오랜 시간 블럭됨
                        //TODO Handling
                    }
                    11 -> {//등록된 지문이 없는 에러 / 얼굴 인식 잠금 해제를 설정하지 않았습니다.
                        //등록된 지문이 없습니다.
                        Toast.makeText(this@MainActivity, "$errString", Toast.LENGTH_SHORT).show()
                        showSecuritySettingDialog(activity)
                    }
                    else -> {
                        Timber.e("errCode Else Block: $errCode")
                    }
                }

            }
            override fun onAuthenticationFailed() { //"지문 인식 실패"
                super.onAuthenticationFailed()
                Timber.d("User biometric rejected.")
            }
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { //"지문 인식 성공"
                super.onAuthenticationSucceeded(result)
                Toast.makeText(this@MainActivity, "생체인식 성공!!!", Toast.LENGTH_SHORT).show()
                Timber.d("Authentication was successful")
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptUi)
    }

    //지문이 등록되어 있지 않은 경우 등록 설정창을 띄운다.
    private fun showSecuritySettingDialog(context: Context) {
        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder
            .setTitle("나의 앱")
            .setMessage("지문 등록이 필요합니다.\n지문등록 설정화면으로 이동하시겠습니까?")
            .setPositiveButton("확인") { _, _ ->
                goBiometricEnrollActivity(context)
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.cancel()
            }
        dialogBuilder.show()
    }

    //지문 등록 화면으로 이동한다.(API 30 부터 사용)
    private fun goBiometricEnrollActivity(context: Context) {
        if (Build.VERSION.SDK_INT > 29) {
            val intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            context.startActivity(intent)
        }
    }

    fun onTestButtonClicked() {
        when(isPossibleToUseBiometric()) {
            BiometricReturnType.SUCCESS -> {
                showBiometricPrompt(this)
            }
            BiometricReturnType.EMPTY -> {
                showSecuritySettingDialog(this)
            }
            else -> {}
        }
    }
}