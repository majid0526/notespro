package com.example.notespro

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import com.android.vending.billing.IInAppBillingService
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * پرداخت درون‌برنامه‌ای مایکت (پروتکل IAB v3). بدون سرور؛ امضای خرید با کلید عمومی RSA روی دستگاه چک می‌شه.
 * خرید Pro «مصرف» (consume) نمی‌شه تا دائمی بمونه.
 */
class MyketBilling(
    private val activity: ComponentActivity,
    private val onPro: () -> Unit
) {
    companion object {
        const val PRO_ID = "pro_unlock"          // باید دقیقاً همین شناسه در پنل مایکت ساخته بشه
        const val MARKET_PKG = "ir.mservices.market"
        // کلید عمومی RSA از پنل مایکت (محرمانه نیست)
        const val RSA_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCEYCZFzkOfZhRmHbW+aXZa0YQ9H7JR/gbYwZVW5shSA5PUv+ORGuQTfpDgRAGqMFeQeVU3JB5o+DP/ZWtMZmCp+2/mYWcf33e1ycl9Rme/IhkTv7lb50iSfMMZ7V+5IrmwnI6m9oLQh/b7AxgF4hZJqMdfBnjXLF9evDvhbZesLQIDAQAB"
        private const val API = 3
        private const val TYPE = "inapp"
    }

    private var service: IInAppBillingService? = null
    var price: String? = null
        private set

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val data = res.data ?: return@registerForActivityResult
        val code = data.getIntExtra("RESPONSE_CODE", -1)
        if (code == 7) { refresh(); return@registerForActivityResult } // قبلاً خریده شده
        if (res.resultCode == ComponentActivity.RESULT_OK && code == 0) {
            val json = data.getStringExtra("INAPP_PURCHASE_DATA")
            val sig = data.getStringExtra("INAPP_DATA_SIGNATURE")
            if (json != null && sig != null && isValidPro(json, sig)) onPro()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            service = IInAppBillingService.Stub.asInterface(b)
            refresh()
        }
        override fun onServiceDisconnected(n: ComponentName?) { service = null }
    }

    fun start() {
        val i = Intent("$MARKET_PKG.InAppBillingService.BIND").setPackage(MARKET_PKG)
        try { activity.bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (_: Exception) {}
    }

    fun stop() {
        try { activity.unbindService(conn) } catch (_: Exception) {}
        service = null
    }

    /** قیمت محصول + بازیابی خرید قبلی */
    fun refresh() {
        val s = service ?: return
        Thread {
            try {
                val q = Bundle().apply { putStringArrayList("ITEM_ID_LIST", arrayListOf(PRO_ID)) }
                val d = s.getSkuDetails(API, activity.packageName, TYPE, q)
                if (d.getInt("RESPONSE_CODE") == 0)
                    price = d.getStringArrayList("DETAILS_LIST")?.firstOrNull()
                        ?.let { JSONObject(it).optString("price") }

                val p = s.getPurchases(API, activity.packageName, TYPE, null)
                if (p.getInt("RESPONSE_CODE") == 0) {
                    val data = p.getStringArrayList("INAPP_PURCHASE_DATA_LIST") ?: arrayListOf()
                    val sigs = p.getStringArrayList("INAPP_DATA_SIGNATURE_LIST") ?: arrayListOf()
                    if (data.indices.any { isValidPro(data[it], sigs.getOrNull(it) ?: "") })
                        activity.runOnUiThread { onPro() }
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun buy() {
        val s = service
        if (s == null) {
            Toast.makeText(activity, "اپ مایکت نصب نیست یا متصل نشد", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val r = s.getBuyIntent(API, activity.packageName, PRO_ID, TYPE, "")
                when (r.getInt("RESPONSE_CODE")) {
                    0 -> {
                        val pi = BundleCompat.getParcelable(r, "BUY_INTENT", PendingIntent::class.java)
                        if (pi != null) activity.runOnUiThread {
                            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    }
                    7 -> refresh()
                    else -> activity.runOnUiThread {
                        Toast.makeText(activity, "خرید ممکن نشد (کد ${r.getInt("RESPONSE_CODE")})", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** چک امضا با کلید RSA + اطمینان از محصول و وضعیت «خریداری‌شده» */
    private fun isValidPro(json: String, signature: String): Boolean = try {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(RSA_KEY, Base64.DEFAULT)))
        val sig = Signature.getInstance("SHA1withRSA").apply { initVerify(key); update(json.toByteArray()) }
        val o = JSONObject(json)
        sig.verify(Base64.decode(signature, Base64.DEFAULT)) &&
            o.optString("productId") == PRO_ID && o.optInt("purchaseState", 0) == 0
    } catch (_: Exception) { false }
}
