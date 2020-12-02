package com.example.myplayer
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


class StorageUtil(context: Context) {
    private val STORAGE = "com.example.myplayer.media"
    private var preferences: SharedPreferences? = null
    private var context = context

    fun storeAudio(arrayList: ArrayList<Audio>){
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)

        var editor = preferences!!.edit()
        var gson= Gson()
        val json = gson.toJson(arrayList)
        editor.putString("audioArrayList",json)
        editor.apply()
    }

    fun loadAudio(): ArrayList<Audio>{
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        var gson = Gson()
        var json= preferences!!.getString("audioArrayList", null)
        var type = object: TypeToken<ArrayList<Audio>>(){}.type
        return gson.fromJson(json, type)
    }

    fun storeAudioIndex(index: Int){
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        var editor = preferences!!.edit()
        editor.putInt("audioIndex", index)
        editor.apply()
    }

    fun loadAudioIndex(): Int{
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        return preferences!!.getInt("audioIndex",-1) //return -1 if no data found
    }

    fun clearCachedAudioPlaylist(){
        preferences = context.getSharedPreferences("STORAGE", Context.MODE_PRIVATE)
        var editor = preferences!!.edit()
        editor.clear()
        editor.commit()
    }

}