package com.example.myplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.CursorWindow
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.provider.MediaStore.*
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myplayer.MediaPlayerService.LocalBinder
import com.google.android.material.floatingactionbutton.FloatingActionButton


class MainActivity : AppCompatActivity() {
    var collapsingImageView: ImageView? = null
    private var player: MediaPlayerService? = null
    var serviceBound: Boolean = false
    var audioList = mutableListOf<com.example.myplayer.Audio>()
    var  requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){ isGranted: Boolean ->
            if(isGranted){
                //Permission is granted
                Toast.makeText(applicationContext, "permission granted", Toast.LENGTH_SHORT)
            }else{
                //Permission not granted explain to user that the feature is unavailable because the features require the permission
                Toast.makeText(applicationContext, "permission denied", Toast.LENGTH_SHORT)
            }
    }


    companion object{
        public const val Broadcast_PLAY_NEW_AUDIO = "com.example.myplayer.PlayNewAudio"

    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        var toolbar= findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar) as androidx.appcompat.widget.Toolbar
        setSupportActionBar(toolbar)
        collapsingImageView = findViewById(R.id.collapsingImageView) as ImageView

        loadCollapsingImage(0)

        askPermission()
        loadAudio()
        initRecyclerView()
        var fab= findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener {
        loadCollapsingImage(0)
        }

    }

   private  fun initRecyclerView(){

       if(audioList.size > 0){
           var rv = findViewById<RecyclerView>(R.id.recyclerview)
           var adapter = RecyclerView_Adapter(audioList, application)
           rv.adapter= adapter
           rv.layoutManager = LinearLayoutManager(this)
           rv.addOnItemTouchListener(CustomTouchListener(this, object : OnItemClickListener {
               override fun onClick(view: View, index: Int) {
                   playAudio(index)
               }
           }))

       }
   }

    private fun loadCollapsingImage(i: Int){
        var array = resources.obtainTypedArray(R.array.images)
        collapsingImageView?.setImageDrawable(array.getDrawable(i))
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        //Inflate the menu. This adds items to the action bar if it is present
        menuInflater.inflate(R.menu.menu_main, menu)
        return true

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        //Handle action bar item clicks here
        //The action bar will automatically handle clicks on the Home/Up button, so long as we specify a parent activity in AndroidManifest.xml
        var id = item.itemId

        //noinspection SimplifiableIfStatement
        if(id == R.id.action_settings){
            return true
        }
        return super.onOptionsItemSelected(item)
    }


    //Binding this Client to the AudioPlayer service
    private var serviceConnection: ServiceConnection? = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder){
            //We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as LocalBinder
            player = binder.getService()
            serviceBound = true
            Toast.makeText(this@MainActivity, "Service Bound", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName){
            serviceBound = false
        }
    }

    private fun playAudio(audioIndex: Int){
        //Check if service is active
        if(!serviceBound){
            //Store Serializable audioList to SharedPreferences
            var storage= StorageUtil(applicationContext)
            storage.storeAudio(audioList!! as ArrayList<Audio>)
            storage.storeAudioIndex(audioIndex)

            val playerIntent = Intent(this, MediaPlayerService::class.java)
            startService(playerIntent)
            bindService(playerIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)

        }else{
            //Store the new audioIndex to SharedPreferences
            var storage = StorageUtil(applicationContext)
            storage.storeAudioIndex(audioIndex)

            //Service is active
            //Send media with Broadcast Receiver
            var broadcastIntent= Intent(Broadcast_PLAY_NEW_AUDIO)
            sendBroadcast(broadcastIntent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ServiceState", serviceBound)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        serviceBound = savedInstanceState.getBoolean("ServiceState")
    }

    override fun onDestroy() {
        super.onDestroy()
        if(serviceBound){
            unbindService(serviceConnection!!)
            //service is active
            player!!.stopSelf()
        }
    }

    private fun loadAudio(){
        //Container for info about each audio file


        val collection =
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                    MediaStore.Audio.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL
                    )
                }else{
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

                }

        //show only music files
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        //Display audio files in alphabetical order based on their display name
        val sortOrder= "${MediaStore.Audio.Media.TITLE} ASC"


        val query = contentResolver.query(
                collection,
                null,
                null,
                null,
                sortOrder
        )

        Log.d("query", "query size is ${query?.columnCount}")


        query?.use { cursor ->
            //Cache column indices
            val idColumn= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val albumColumn= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val artistColumn= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataColumn= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()){
                //Get values of columns for a given audio
                val data = cursor.getString(dataColumn)
                val title = cursor.getString(titleColumn)
                val album = cursor.getString(albumColumn)
                val artist = cursor.getString(artistColumn)
                val id = cursor.getLong(idColumn)

                val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                Log.d("query", "DATA: ${data}")
                Log.d("query", "TITLE: ${title}")
                Log.d("query", "ALBUM: ${album}")
                Log.d("query", "ARTIST: ${artist}")
                Log.d("query", "ID: ${id}")


                //Stores column values and the contentUri in a local object that represent the media file
                audioList.plusAssign(Audio(data, title, album, artist, contentUri.toString()))

            }
        }

    }

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