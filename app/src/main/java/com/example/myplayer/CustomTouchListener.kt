package com.example.myplayer

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView


open class CustomTouchListener(context: Context, clickListener: OnItemClickListener) : RecyclerView.OnItemTouchListener{

    //Gesture detector to intercept touch events
    var gestureDetector: GestureDetector = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener(){
        override fun onSingleTapUp(e: MotionEvent) : Boolean{
            return true
        }
    })
    private var clickListener = clickListener

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {

        var child = rv.findChildViewUnder(e.x,e.y)
        if(child != null && clickListener != null && gestureDetector.onTouchEvent(e)){
            clickListener.onClick(child,rv.getChildLayoutPosition(child))
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {

    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {

    }

}