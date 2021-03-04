package com.example.myplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager

import android.os.Bundle

import android.provider.MediaStore.*
import android.view.Menu

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

import com.example.myplayer.fragments.MediaItemFragment
import com.example.myplayer.utils.InjectorUtils
import com.example.myplayer.viewmodels.MainActivityViewModel
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext


class MainActivity : AppCompatActivity() {

    private val viewModel by viewModels<MainActivityViewModel>{
        InjectorUtils.provideMainActivityViewModel(this)
    }

    private var castContext: CastContext? = null

    var  requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ isGranted: Boolean ->
            if(isGranted){
                //Permission is granted
                Toast.makeText(applicationContext, "permission granted", Toast.LENGTH_SHORT)
            }else{
                //Permission not granted explain to user that the feature is unavailable because the features require the permission
                Toast.makeText(applicationContext, "permission denied", Toast.LENGTH_SHORT)
            }
    }





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        //TODO: Initialize cast context

        setContentView(R.layout.activity_main)

        //Ensure volume controls on phone can adjust the music volume while using app
        volumeControlStream = AudioManager.STREAM_MUSIC

        //Get permissions for reading local music files
        askPermission()

        /**
         * Observe [MainActivityViewModel.navigateToFragment] fpr [Event]s that request a fragment
         * swap
         */
        viewModel.navigateToFragment.observe(this, Observer {
            it?.getContentIfNotHandled()?.let { fragmentNavigationRequest ->
                val transaction = supportFragmentManager.beginTransaction()
                transaction.replace(
                        R.id.fragmentContainer, fragmentNavigationRequest.fragment,
                        fragmentNavigationRequest.tag
                )
                if(fragmentNavigationRequest.backStack) transaction.addToBackStack(null)
                transaction.commit()
            }
        })

        /**
         * Observe changes to [MainActivityViewModel.rootMediaId]. When app starts, and UI connects
         * to [MusicService], this will be updated and the app wil show the initial list of
         * media items
         */

        viewModel.rootMediaId.observe(this,
            Observer<String> { rootMediaId->
                rootMediaId?.let { navigateToMediaItem(it) }
            })

        /**
         * Observe [MainActivityViewModel.navigateToMediaItem] for [Event]s indicating
         * the user has request to browse to a difference [MediaItemData]
         */

        viewModel.navigateToMediaItem.observe(
                this,
                Observer {
                    it?.getContentIfNotHandled()?.let { mediaId ->
                        navigateToMediaItem(mediaId)
                    }
                }
        )
    }

    @Override
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_activity_menu,menu)
        /**
         * Set up a MediaRouteButton to allow the user to control the current media playback route
         */
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        return true
    }

    private fun navigateToMediaItem(mediaId: String){
        var fragment: MediaItemFragment? = getBrowseFragment(mediaId)
        if(fragment==null){
            fragment = MediaItemFragment.newInstance(mediaId)
            //If this is not the top level media (root) we add it to the fragment back stack,
            //so that the actionbar toggle and Back will work properly
            viewModel.showFragment(fragment, !isRootId(mediaId),mediaId)
        }
    }

    private fun isRootId(mediaId: String)= mediaId == viewModel.rootMediaId.value

    private fun getBrowseFragment(mediaId: String): MediaItemFragment? {
        return supportFragmentManager.findFragmentByTag(mediaId) as MediaItemFragment?
    }

    @SuppressLint("NewApi")
    private fun askPermission(){
        when{
            ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED ->{
                //you can use the api that requires the permission
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)->{
                Toast.makeText(
                        this,
                        "rationale for app requiring this permission should include a cancel or no thanks",
                        Toast.LENGTH_SHORT
                )
            }else->{
                //You cna directly ask for the permission.
                //The registered ActivityResultCallback gets the result of  this request
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

}