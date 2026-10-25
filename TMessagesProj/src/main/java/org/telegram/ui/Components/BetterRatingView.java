/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Created by grishka on 10.02.17.
 */

import app.nimarkogram.messenger.icons.IconResources;
public class BetterRatingView extends View {
	private Drawable filledStar, hollowStar;
	private Resources starResources;
	private long starGeneration;
	private int starDensity;
	private boolean starColorsSet;
	private int filledColor, hollowColor;
	private int numStars = 5;
	private int selectedRating = 0;
	private OnRatingChangeListener listener;

	public BetterRatingView(Context context) {
		super(context);
		updateStarDrawables();
	}

	private void updateStarDrawables() {
		Resources resources = getContext().getResources();
		long generation = resources instanceof IconResources
				? ((IconResources) resources).getReplacementGeneration() : -1L;
		int density = resources.getDisplayMetrics().densityDpi;
		if (starResources != resources || starGeneration != generation || starDensity != density) {
			filledStar = resources.getDrawable(R.drawable.ic_rating_star_filled).mutate();
			hollowStar = resources.getDrawable(R.drawable.ic_rating_star).mutate();
			starResources = resources;
			starGeneration = generation;
			starDensity = density;
			starColorsSet = false;
		}
		int selected = Theme.getColor(Theme.key_dialogTextBlue);
		int empty = Theme.getColor(Theme.key_dialogTextHint);
		if (!starColorsSet || filledColor != selected || hollowColor != empty) {
			filledStar.setColorFilter(selected, PorterDuff.Mode.SRC_IN);
			hollowStar.setColorFilter(empty, PorterDuff.Mode.SRC_IN);
			filledColor = selected;
			hollowColor = empty;
			starColorsSet = true;
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		setMeasuredDimension(numStars * AndroidUtilities.dp(32) + (numStars - 1) * AndroidUtilities.dp(16), AndroidUtilities.dp(32));
	}

	@Override
	protected void onDraw(Canvas canvas) {
		updateStarDrawables();
		for (int i = 0; i < numStars; i++) {
			Drawable star = i < selectedRating ? filledStar : hollowStar;
			int left = i * AndroidUtilities.dp(32 + 16);
			star.setBounds(left, 0, left + AndroidUtilities.dp(32), AndroidUtilities.dp(32));
			star.draw(canvas);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		float offset = AndroidUtilities.dp(-8);
		for (int i = 0; i < numStars; i++) {
			if (event.getX() > offset && event.getX() < offset + AndroidUtilities.dp(32 + 16)) {
				if (selectedRating != i + 1) {
					selectedRating = i + 1;
					if (listener != null)
						listener.onRatingChanged(selectedRating);
					invalidate();
					break;
				}
			}
			offset += AndroidUtilities.dp(32 + 16);
		}
		return true;
	}

	public int getRating() {
		return selectedRating;
	}

	public void setOnRatingChangeListener(OnRatingChangeListener l) {
		listener = l;
	}

	public interface OnRatingChangeListener {
		void onRatingChanged(int newRating);
	}
}
