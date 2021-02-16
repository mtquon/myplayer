package com.example.myplayer.media

import android.content.Context
import android.Manifest.permission.MEDIA_CONTENT_CONTROL
import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED
import android.content.res.XmlResourceParser
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.annotation.XmlRes
import androidx.core.app.NotificationManagerCompat
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.ThreadPoolExecutor

/**
 * Validates that the calling package is authorized to browse a [MediaBrowserServiceCompat]
 *
 * The list of allowed signing certificates adn their corresponding package name is defined in
 * res/xml/allowed_media_browser_callers.xml
 *
 * If you want to add a new caller to allowed_media_browser_callers.xml and you don't know its
 * signature, this class will print to logcat (INFO level) a message with the proper xml tags
 * to add to allow the caller.
 *
 * For more info see res/xml/allowed_media_browser_callers.xml
 */
class PackageValidator(context: Context, @XmlRes xmlResId: Int) {
    private val context: Context
    private val packageManager: PackageManager

    private val certificateAllowList: Map<String, KnownCallerInfo>
    private val platformSignature: String

    private val callerChecked = mutableMapOf<String, Pair<Int, Boolean>>()

    init{
        val parser= context.resources.getXml(xmlResId)
        this.context = context.applicationContext
        this.packageManager = this.context.packageManager

        certificateAllowList = buildCertificateAllowList(parser)

        platformSignature = getSystemSignature()
    }

    /**
     * Check whether the caller attempting to connect to a [MediaBrowserServiceCompat] is known.
     * See [MusicService.getGetRoot] for where this is utilized
     *
     * @param callingPackage The package name of the caller.
     * @param callingUid The user id of the caller.
     * @return `true` if the caller is known, `false` otherwise.
     */

    fun isKnownCaller(callingPackage: String, callingUid: Int): Boolean{
        //if the caller has already been checked, return the previous result here.
        val(checkUid,checkResult)= callerChecked[callingPackage] ?: Pair(0,false)
        if (checkUid==callingUid){
            return checkResult
        }
        /**
         * Because some of these checks can be slow, we save the result in [callerChecked] after
         * this code is run,
         *
         * In particular, there's little reason to recompute the calling package's certificate
         * signature (SHA-256) each call
         *
         * This is safe to do as we know the UID matches the package's UID (from the check above),
         * and app UIDs are set at install time. Additionally a package name+ UID is guaranteed to
         * be constant until a reboot. (After a reboot then a previously assigned UID could be
         * reassigned)
         */

        //Build the caller info for the rest of the check here.
        val callerPackageInfo = buildCallerInfo(callingPackage)
            ?: throw IllegalStateException("Caller wasn't found in the system?")

        //Verify that things aren't broken (this test should always pass.)
        if(callerPackageInfo.uid != callingUid){
            throw IllegalStateException("Caller's package UID doesn't match caller's actually UID?")
        }

        val callerSignature = callerPackageInfo.signature
        val isPackageInAllowList = certificateAllowList[callingPackage]?.signatures?.first{
            it.signature == callerSignature
        } != null

        val isCallerKnown = when{
            //If it's our own app making the call allow it
            callingUid == Process.myUid() -> true
            //if it's one of the apps on the allow list allow it.
            isPackageInAllowList -> true
            //If the system is making the call, allow it
            callingUid ==Process.SYSTEM_UID -> true
            //if the app was signed by the same certificates as the platform itself, also allow it.
            callerSignature == platformSignature -> true
            /**
             * [MEDIA_CONTENT_CONTROL] permission is only available to system applications, and
             * while it isn't required to allow these apps to connect to a
             * [MediaBrowserServiceCompat], allowing this ensures optimal compatability with apps
             * such as Android TV and the Google Assistant
             */

            callerPackageInfo.permissions.contains(MEDIA_CONTENT_CONTROL)-> true
            /**
             * If the calling app has a notification listener it is able to retrieve notifications
             * and can connect to an active [MediaSessionCompat]
             *
             * It's not required to allow apps with a notification listener to connect to your
             * [MediaBrowserServoceCompat], but it does allow easy compatability with apps
             * such as Wear OS
             */
            NotificationManagerCompat.getEnabledListenerPackages(this.context)
                .contains(callerPackageInfo.packageName) -> true

            //If non of the pervious checks succeeded then the caller is unrecognized
            else-> false
        }

        if(!isCallerKnown){
            logUnownCaller(callerPackageInfo)

        }

        //Save our work for next time
        callerChecked[callingPackage] = Pair(callingUid,isCallerKnown)
        return isCallerKnown
    }
    /**
     * Logs an info level message with details of how to add a caller to the allowed callers list
     * when teh app is debuggable.
     */
    private  fun logUnownCaller(callerPackageInfo: CallerPackageInfo){
        if(BuildConfig.DEBUG && callerPackageInfo.signature != null){
            val formattedLog=
                "Caller has a valid certificate, but its package doesn't..."
            Log.i(TAG, formattedLog)
        }
    }

    /**
     * Builds a [callerPackageInfo] for a given package that can be used for all the various check
     * that are performed before allowing an app to connect to a [MediaBrowsercompat]
     */
    private fun buildCallerInfo(callingPackage: String): CallerPackageInfo?{
        val packageInfo = getPackageInfo(callingPackage) ?: return null

        val appName = packageInfo.applicationInfo.loadLabel(packageManager).toString()
        val uid = packageInfo.applicationInfo.uid
        val signature =getSignature(packageInfo)

        val requestedPermissions = packageInfo.requestedPermissions
        val permissionFlags = packageInfo.requestedPermissionsFlags
        val activePermissions = mutableSetOf<String>()
        requestedPermissions?.forEachIndexed { index, permission ->
            if(permissionFlags[index] and REQUESTED_PERMISSION_GRANTED != 0){
                activePermissions += permission
            }
        }
        return CallerPackageInfo(appName,callingPackage,uid,signature,activePermissions.toSet())
    }

    /**
     * Gets the signature of a given's package's [PackageInfo]
     *
     * The "signature" is a SHA-256 hash of the public key of the signing certificate used by
     * the app.
     *
     * If the app is not found, or if the app does not have exactly one signature, this method
     * return `null` as the signature
     */
    @Suppress("deprecation")
    private fun getSignature(packageInfo: PackageInfo): String? =
        if(packageInfo.signatures ==null || packageInfo.signatures.size != 1){
            //Security best practices dicate that an app should be signed with exactly one (1)
            //signature. Because of this, if there are multiple signatures, reject it
            null
        }else{
            val certificate = packageInfo.signatures[0].toByteArray()
            getSignatureSha256(certificate)
        }

    /**
     * Looks up the [PackageInfo] for a package name.
     * This requests both the signatures (for checking if an app is on the allow list) and
     * the app's permissions, which allow for more flexibility in the allow list.
     *
     * @return [PackageInfo] for the package name or null if it's not found
     */
    @Suppress("deprecation")
    @SuppressLint("PackageManagerGetSignatures")
    private fun getPackageInfo(callingPackage: String): PackageInfo? =
        packageManager.getPackageInfo(
            callingPackage,
            PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
        )


    private fun buildCertificateAllowList(parser: XmlResourceParser): Map<String, PackageValidator.KnownCallerInfo> {

        val certificateAllowList = LinkedHashMap<String, KnownCallerInfo>()
        try{
            var eventType = parser.next()
            while(eventType != XmlResourceParser.END_DOCUMENT){
                if(eventType == XmlResourceParser.START_TAG){
                    val callerInfo = when(parser.name){
                        "signing_certificate" -> parseV1Tag(parser)
                        "signature" -> parseV2Tag(parser)
                        else->null
                    }

                    callerInfo?.let{ info ->
                        val packageName = info.packageName
                        val existingCallerInfo = certificateAllowList[packageName]
                        if (existingCallerInfo != null) {
                            existingCallerInfo.signatures += callerInfo.signatures
                        }else{
                            certificateAllowList[packageName] = callerInfo
                        }

                    }
                }
                eventType = parser.next()

            }

        }catch (xmlException: XmlPullParserException){
            Log.e(TAG,"Could not read allowed callers from XML.", xmlException)
        }catch(ioException: IOException){
            Log.e(TAG,"Could not read allowed callers form XML",ioException)
        }
        return certificateAllowList
    }


    /**
     * Parses a v1 format tag. see allowed_media_browser_callers.xml for more details
     */
    private fun parseV1Tag(parser: XmlResourceParser): KnownCallerInfo{
        val name= parser.getAttributeValue(null,"name")
        val packageName = parser.getAttributeValue(null,"package")
        val isRelease = parser.getAttributeBooleanValue(null,"release",false)
        val certificate = parser.nextText().replace(WHITESPACE_REGEX,"")
        val signature = getSignatureSha256(certificate)

        val callerSignature = KnownSignature(signature,isRelease)
        return KnownCallerInfo(name, packageName, mutableSetOf(callerSignature))
    }

    /**
     * Parses a v2 format tag. see allowed_media_browser_callers.xml for more details
     */
    private fun parseV2Tag(parser: XmlResourceParser): KnownCallerInfo{
        val name = parser.getAttributeValue(null,"name")
        val packageName = parser.getAttributeValue(null,"package")

        val callerSignatures= mutableSetOf<KnownSignature>()
        var eventType = parser.next()
        while(eventType != XmlResourceParser.END_TAG){
            val isRelease = parser.getAttributeBooleanValue(null,"release",false)
            val signature = parser.nextText().replace(WHITESPACE_REGEX,"").toLowerCase()
            callerSignatures += KnownSignature(signature, isRelease)

            eventType = parser.next()
        }

        return KnownCallerInfo(name, packageName, callerSignatures)
    }

    /**
     * Finds the Android platform signing key signature. This key is never null
     */
    private fun getSystemSignature(): String  =
        getPackageInfo(ANDROID_PLATFORM)?.let { playformInfo->
            getSignature(playformInfo)
        } ?: throw IllegalStateException("Playform signature not found")

    /**
     * Create a SHA-256 signature given as Base64 encoded certificate
     */
    private fun getSignatureSha256(certificate: String): String{
        return getSignatureSha256(Base64.decode(certificate,Base64.DEFAULT))
    }

    private fun getSignatureSha256(certificate: ByteArray): String{
        val md: MessageDigest
        try {
            md= MessageDigest.getInstance("SHA256")
        }catch (noSuchAlgorithmException: NoSuchAlgorithmException){
            Log.e(TAG,"No such algorithm: $noSuchAlgorithmException")
            throw RuntimeException("Could not find SHA256 hash algorithm", noSuchAlgorithmException)
        }
        md.update(certificate)
        /**
         * This code takes the byte array generated by `md.digest()` and joins each of the bytes
         * to a string, applying teh string format `%02x` on each digit before it's appended, with
         * a colon (':') between each of the items.
         * For example: input=[0,2,4,6,8,10,12], output="00:02:04:06:08:0a:0c"
         */
        return md.digest().joinToString(":"){ String.format("%02x",it)}
    }

    private data class KnownCallerInfo (
        internal val name: String,
        internal val packageName: String,
        internal val signatures: MutableSet<KnownSignature>
    )

    private data class KnownSignature(
        internal val signature: String,
        internal val release: Boolean
    )

    /**
     * Convenience class to hold all of the info about  an app that's being checked to see if it's
     * a known caller.
     */

    private data class CallerPackageInfo(
        internal val name: String,
        internal val packageName: String,
        internal val uid: Int,
        internal val signature: String?,
        internal val permissions: Set<String>
    )
}

private const val TAG = "PackageValidator"
private const val ANDROID_PLATFORM = "android"
private val WHITESPACE_REGEX = "\\s|\\n".toRegex()