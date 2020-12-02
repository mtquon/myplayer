package com.example.myplayer

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.*



class RecyclerView_Adapter(list: List<Audio>, context: Context) :
    RecyclerView.Adapter<ViewHolder>() {
    var list : List<Audio> = Collections.emptyList()
    lateinit var context :Context

    init{
        this.list=list
        this.context= context
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        //Inflate the layout, initialize the View Holder
        Log.d("rv adapter", "parent viewgroup is : $parent and viewtype is : $viewType")
        var v = LayoutInflater.from(parent.context).inflate(R.layout.item_layout, parent, false)
        var holder = ViewHolder(v)
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        //Use the provided View Holder on the onCreateViewHolder method to populate the current row on the RecyclerView
        holder.title.text = list[position].title
    }

    override fun getItemCount() : Int{
        //returns the number of elements the RecyclerView will display
        return list.size
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
    }

}

class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView){
    var title: TextView
    var play_pause: ImageView


    init {
        title= itemView.findViewById(R.id.title) as TextView
        play_pause = itemView.findViewById(R.id.play_pause) as ImageView
    }
}




