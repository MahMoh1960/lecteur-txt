package ma.lecteurtxt;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;

/** Conteneur qui détecte le zoom à deux doigts et le signale à l'activité. */
public class ZoomLayout extends FrameLayout {

    interface Listener {
        void onScale(float factor);
    }

    private final ScaleGestureDetector detector;
    private Listener listener;

    public ZoomLayout(Context context) {
        super(context);
        detector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        if (listener != null) {
                            listener.onScale(d.getScaleFactor());
                        }
                        return true;
                    }
                });
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        detector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return ev.getPointerCount() > 1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return detector.isInProgress() || ev.getPointerCount() > 1;
    }
}
