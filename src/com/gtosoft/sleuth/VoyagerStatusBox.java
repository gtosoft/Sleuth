package com.gtosoft.sleuth;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.widget.ImageView;
import android.widget.TextView;

public class VoyagerStatusBox {
	
	int mImages[] = {};
	int mTVStatus = 0;
	BitmapDrawable mIconDrawables [];
	BitmapDrawable mIconCheckmark;
	Context mCtx = null;
	TextView mtvStatus = null;
	int mMaxLevel = 2 * 3 - 1; // 0-5
	Activity mParentActivity = null;
	
	Handler mUIHandler = new Handler();
	
	public VoyagerStatusBox(Context mActivityContext, int drwImage1, int drwImage2, int drwImage3, int tvStatus) {
		mImages = new int[] {drwImage1, drwImage2, drwImage3};
		mTVStatus = tvStatus;
		mCtx = mActivityContext;
		
		mParentActivity = (Activity) mCtx;

		// get bitmapdrawables for the potential icons. 
		mIconDrawables = new BitmapDrawable [] {
				(BitmapDrawable) mParentActivity.getResources().getDrawable(R.drawable.signal),
				(BitmapDrawable) mParentActivity.getResources().getDrawable(R.drawable.obdport),
				(BitmapDrawable) mParentActivity.getResources().getDrawable(R.drawable.magnifycargray)
		};

		// get bitmapdrawables for the checkmark overlay. 
		// TODO: Use a real checkmark overlay. 
		mIconCheckmark = (BitmapDrawable)  mParentActivity.getResources().getDrawable(R.drawable.checkmark);
		
		mtvStatus = (TextView) mParentActivity.findViewById(tvStatus);
		
	}
	
	/**
	 * Sets the message on the status textview. Does so on the ui thread as stipulated for views.
	 */
	public void addMessage (final String m) {
		mUIHandler.post(new Runnable () {
			final TextView t = mtvStatus; 
			public void run () {
				t.setText(m);
			} // end of run method.
		});// end of post method. 
	}// end of addmessage method. 


	public void setStatusLevel (String statusMessage, int statusLevel) { 
		setIconsBasedOnLevel (statusLevel);
		addMessage(statusMessage);
	}
	
	/**
	 * 
	 * @return
	 */
	private boolean setIconsBasedOnLevel (int statusLevel) {

		if (statusLevel == 0) {
			setIconLevel(0, 0);
			setIconLevel(1, 0);  
			setIconLevel(2, 0);  
		}
		
		if (statusLevel == 1) {
			setIconLevel(0, 1);
			setIconLevel(1, 0);  
			setIconLevel(2, 0);  
		}

		if (statusLevel == 2) {
			setIconLevel(0, 2); 
			setIconLevel(1, 1); 
			setIconLevel(2, 0);
		}

		if (statusLevel == 3) {
			setIconLevel(0, 2); 
			setIconLevel(1, 2); 
			setIconLevel(2, 1);
		}
		
		if (statusLevel == 4) {
			setIconLevel(0, 2); 
			setIconLevel(1, 2);  
			setIconLevel(2, 2);  
		}
		
				
		return true;
	}

	/**
	 * Index: 0-2
	 * Level: 0-1
	 * @param iconIndex
	 * @param iconLevel
	 * @return - false if something bombed. otherwise true. 
	 */
	private boolean setIconLevel (int iconIndex, int iconLevel) {
		ImageView iv;
		
		try {
			iv = (ImageView) mParentActivity.findViewById(mImages[iconIndex]);
		} catch (Exception e) {
			return false;
		}

		setImage (iv,mIconDrawables[iconIndex]);
		
		// throw a checkmark on top. 
		if (iconLevel > 1) {
			setImageWithOverlay (iv, mIconDrawables[iconIndex], mIconCheckmark);
		}
		
		return true;
	}

	/**
	 * Builds an image overlay drawable containing the top image on top of the bottom image. We then set that as the drawable for iv. 
	 * @param iv - the image view. 
	 * @param imageBottom - the bottom image drawable.
	 * @param imageTop - the top image drawable. 
	 */
	private void setImageWithOverlay(ImageView iv, BitmapDrawable imageBottom, BitmapDrawable imageTop) {
		Drawable[] layers = new Drawable[] {imageBottom, imageTop};
		LayerDrawable layerDrawable = new LayerDrawable(layers);
		
		setImage(iv,layerDrawable);
	}

	
	/**
	 * sets the given image view to the given bitmap. does so on the main ui thread. 
	 * @param iv
	 * @param bd
	 */
	private void setImage (final ImageView iv, final BitmapDrawable bd) {
		mUIHandler.post(new Runnable () {
			public void run () {
				iv.setImageDrawable(bd);
			}
		});
	}

	/**
	 * overloaded method that supports layerdrawables. does the image setting on the main thread. 
	 * @param iv
	 * @param bd
	 */
	private void setImage (final ImageView iv, final LayerDrawable bd) {
		mUIHandler.post(new Runnable () {
			public void run () {
				iv.setImageDrawable(bd);
			}
		});
	}
	
	/**
	 * give up resources. 
	 */
	public void shutdown () {
		addMessage("shutting down");
		mCtx = null;
		mUIHandler = null;
	}
	

}
