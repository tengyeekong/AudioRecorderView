package com.tougee.recorderview

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.content.res.AppCompatResources
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent.*
import android.view.View
import android.view.View.OnTouchListener
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlinx.android.synthetic.main.view_audio_record.view.*
import kotlin.math.abs

class AudioRecordView : FrameLayout {

    companion object {
        const val AUDIO = 1
        const val VIDEO = 2

        const val RECORD_DELAY = 200L
        const val RECORD_TIP_MILLIS = 2000L

        const val ANIMATION_DURATION = 250L
    }

    lateinit var callback: Callback
    lateinit var activity: Activity

    private var recordIconStatus = AUDIO
        set(value) {
            if (value == field) return

            field = value
            checkRecordIb()
        }

    private var isRecording = false
    private var upBeforeGrant = false
    private var allowHaptic = true

    private var audioDrawable: Drawable = resources.getDrawable(R.drawable.ic_record_mic_black, null)
    private val videoDrawable: Drawable by lazy { resources.getDrawable(R.drawable.ic_record_mic_black, null) }

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        LayoutInflater.from(context).inflate(R.layout.view_audio_record, this, true)
        slide_panel.callback = chatSlideCallback
        record_circle.callback = recordCircleCallback
        record_ib.setOnTouchListener(recordOnTouchListener)
        init(context, attrs, defStyleAttr)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(
                    attrs, R.styleable.AudioRecordView,
                    defStyleAttr, -1
            )

            val slideToCancelText = typedArray.getString(R.styleable.AudioRecordView_slide_to_cancel_text)
            val cancelText = typedArray.getString(R.styleable.AudioRecordView_cancel_text)
            val tipsText = typedArray.getString(R.styleable.AudioRecordView_tips_text)
            val tipsTextColor = typedArray.getColor(R.styleable.AudioRecordView_tips_text_color, -1)
            val tipsBgColor = typedArray.getColor(R.styleable.AudioRecordView_tips_bg_color, -1)
            val blinkColor = typedArray.getColor(R.styleable.AudioRecordView_blink_color, -1)
            val cancelTextColor = typedArray.getColor(R.styleable.AudioRecordView_cancel_text_color, -1)
            val slideToCancelTextColor = typedArray.getColor(R.styleable.AudioRecordView_slide_to_cancel_text_color, -1)
            val btnBgColor = typedArray.getColor(R.styleable.AudioRecordView_btn_bg_color, -1)
            val lockIconColor = typedArray.getColor(R.styleable.AudioRecordView_lock_icon_color, -1)
            val timerColor = typedArray.getColor(R.styleable.AudioRecordView_timer_color, -1)
            val panelBgColor = typedArray.getColor(R.styleable.AudioRecordView_panel_bg_color, -1)
            val micIconRes = typedArray.getResourceId(R.styleable.AudioRecordView_mic_icon, -1)
            val lockedMicIconRes = typedArray.getResourceId(R.styleable.AudioRecordView_locked_mic_icon, -1)
            val lockedSendIconRes = typedArray.getResourceId(R.styleable.AudioRecordView_locked_send_icon, -1)

            slideToCancelText?.let { slide_panel.setSlideText(it) }
            cancelText?.let { slide_panel.setCancelText(it) }
            tipsText?.let { record_tip_tv.text = it }

            if (tipsTextColor != -1) record_tip_tv.setTextColor(tipsTextColor)
            if (tipsBgColor != -1) record_tip_tv.backgroundTintList = ColorStateList.valueOf(tipsBgColor)
            if (slideToCancelTextColor != -1) slide_panel.setSlideCancelColor(slideToCancelTextColor)
            if (cancelTextColor != -1) slide_panel.setCancelColor(cancelTextColor)
            if (blinkColor != -1) slide_panel.setBlinkColor(blinkColor)
            if (btnBgColor != -1) record_circle.setBtnBgColor(btnBgColor)
            if (lockIconColor != -1) record_circle.setLockIconColor(lockIconColor)
            if (timerColor != -1) slide_panel.setTimerColor(timerColor)
            if (panelBgColor != -1) slide_panel.setPanelBgColor(panelBgColor)

            if (micIconRes != -1) {
                AppCompatResources.getDrawable(context, micIconRes)?.let {
                    record_ib.setImageDrawable(it)
                    audioDrawable = it
                }
            }
            if (lockedMicIconRes != -1) record_circle.setMicIcon(lockedMicIconRes)
            if (lockedSendIconRes != -1) record_circle.setSendIcon(lockedSendIconRes)

            typedArray.recycle()
        }
    }

    fun setMaxTime(maxTimeValue: Int) {
        slide_panel.setMaxTime(maxTimeValue)
    }

    fun setAllowHaptic(allowHaptic: Boolean) {
        this.allowHaptic = allowHaptic
        slide_panel.setAllowHaptic(allowHaptic)
        record_circle.setAllowHaptic(allowHaptic)
    }

    fun cancelExternal() {
        removeCallbacks(recordRunnable)
        cleanUp()
        updateRecordCircleAndSendIcon()
        slide_panel.parent.requestDisallowInterceptTouchEvent(false)
    }

    private fun cleanUp() {
        startX = 0f
        originX = 0f
        isRecording = false
    }

    private fun checkRecordIb() {
        val d = when (recordIconStatus) {
            AUDIO -> audioDrawable
            VIDEO -> videoDrawable
            else -> throw IllegalArgumentException("error send status")
        }
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        record_ib.setImageDrawable(d)
    }

    private fun handleCancelOrEnd(cancel: Boolean) {
        if (cancel) callback.onRecordCancel() else callback.onRecordEnd()
        cleanUp()
        updateRecordCircleAndSendIcon()
    }

    private fun updateRecordCircleAndSendIcon() {
        if (isRecording) {
            record_circle.visibility = View.VISIBLE
            record_circle.setAmplitude(.0)
            ObjectAnimator.ofFloat(record_circle, "scale", 1f).apply {
                interpolator = DecelerateInterpolator()
                duration = 200
                addListener(onEnd = {
                    record_circle.visibility = View.VISIBLE
                }, onCancel = {
                    record_circle.visibility = View.VISIBLE
                })
            }.start()
            record_ib.animate().setDuration(200).alpha(0f).start()
            slide_panel.onStart()
        } else {
            ObjectAnimator.ofFloat(record_circle, "scale", 0f).apply {
                interpolator = AccelerateInterpolator()
                duration = 200
                addListener(onEnd = {
                    record_circle.visibility = View.GONE
                    record_circle.setSendButtonInvisible()
                }, onCancel = {
                    record_circle.visibility = View.GONE
                    record_circle.setSendButtonInvisible()
                })
            }.start()
            record_ib.animate().setDuration(200).alpha(1f).start()
            slide_panel.onEnd()
        }
    }

    private fun clickSend() {
        when (recordIconStatus) {
            AUDIO -> {
                if (record_tip_tv.visibility == View.INVISIBLE) {
                    record_tip_tv.fadeIn(ANIMATION_DURATION)
                    postDelayed(hideRecordTipRunnable, RECORD_TIP_MILLIS)
                } else {
                    removeCallbacks(hideRecordTipRunnable)
                }
                postDelayed(hideRecordTipRunnable, RECORD_TIP_MILLIS)
            }
            VIDEO -> {
                recordIconStatus = AUDIO
            }
        }
    }

    private var originX = 0f
    private var originY = 0f
    private var startX = 0f
    private var startY = 0f
    private var isVertical = false
    private var isHorizontal = false
    private var orientationDiff = 100f
    private var startTime = 0L
    private var triggeredCancel = false
    private var hasStartRecord = false
    private var locked = false
    private var maxScrollX = context.dip(100f)

    @SuppressLint("ClickableViewAccessibility")
    private val recordOnTouchListener = OnTouchListener { _, event ->
        when (event.action) {
            ACTION_DOWN -> {
                if (record_circle.sendButtonVisible) {
                    return@OnTouchListener false
                }

                if (allowHaptic) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                }
                originX = event.rawX
                originY = event.rawY
                startX = event.rawX
                startY = event.rawY
                val w = slide_panel.slideWidth
                if (w > 0) {
                    maxScrollX = w
                }
                startTime = System.currentTimeMillis()
                hasStartRecord = false
                locked = false
                postDelayed(recordRunnable, RECORD_DELAY)
                return@OnTouchListener true
            }
            ACTION_MOVE -> {
                if (record_circle.sendButtonVisible || !hasStartRecord) return@OnTouchListener false

                val xDiff = abs(event.rawX - originX)
                val yDiff = abs(event.rawY - originY)
                if (xDiff < orientationDiff && yDiff < orientationDiff) {
                    isHorizontal = false
                    isVertical = false
                }

                if (!isHorizontal) {
                    if (yDiff >= orientationDiff) isVertical = true

                    val lockStatus = record_circle.setLockTranslation(event.y)
                    if (lockStatus == 2) {
                        ObjectAnimator.ofFloat(record_circle, "lockAnimatedTranslation",
                                record_circle.startTranslation).apply {
                            duration = 150
                            interpolator = DecelerateInterpolator()
                            doOnEnd {
                                if (allowHaptic) {
                                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                                }
                                locked = true
                            }
                        }.start()
                        slide_panel.toCancel()
                        return@OnTouchListener false
                    }
                }

                if (!isVertical) {
                    if (xDiff >= orientationDiff) isHorizontal = true

                    val moveX = event.rawX
                    if (moveX != 0f) {
                        slide_panel.slideText(startX - moveX)
                        if (originX - moveX > maxScrollX) {
                            if (allowHaptic) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            }
                            removeCallbacks(recordRunnable)
                            removeCallbacks(checkReadyRunnable)
                            handleCancelOrEnd(true)
                            slide_panel.parent.requestDisallowInterceptTouchEvent(false)
                            triggeredCancel = true
                            return@OnTouchListener false
                        }
                    }
                    startX = moveX
                }
            }
            ACTION_UP, ACTION_CANCEL -> {
                if (triggeredCancel) {
                    cleanUp()
                    triggeredCancel = false
                    return@OnTouchListener false
                }

                if (!hasStartRecord) {
                    removeCallbacks(recordRunnable)
                    removeCallbacks(checkReadyRunnable)
                    cleanUp()
                    if (!post(sendClickRunnable)) {
                        clickSend()
                    }
                } else if (hasStartRecord && !locked && System.currentTimeMillis() - startTime < 500) {
                    removeCallbacks(recordRunnable)
                    removeCallbacks(checkReadyRunnable)
                    // delay check sendButtonVisible
                    postDelayed({
                        if (!record_circle.sendButtonVisible) {
                            handleCancelOrEnd(true)
                        } else {
                            record_circle.sendButtonVisible = false
                        }
                    }, 200)
                    return@OnTouchListener false
                }

                if (isRecording && !record_circle.sendButtonVisible) {
                    handleCancelOrEnd(event.action == ACTION_CANCEL)
                } else {
                    cleanUp()
                }

                if (!callback.isReady()) {
                    upBeforeGrant = true
                }
            }
        }
        return@OnTouchListener true
    }

    private val sendClickRunnable = Runnable { clickSend() }

    private val hideRecordTipRunnable = Runnable {
        if (record_tip_tv.visibility == View.VISIBLE) {
            record_tip_tv.fadeOut(ANIMATION_DURATION)
        }
    }

    private val recordRunnable: Runnable by lazy {
        Runnable {
            hasStartRecord = true
            removeCallbacks(hideRecordTipRunnable)
            post(hideRecordTipRunnable)

            if (recordIconStatus == AUDIO) {
                if (ContextCompat.checkSelfPermission(activity, (Manifest.permission.RECORD_AUDIO)) != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(activity, (Manifest.permission.WRITE_EXTERNAL_STORAGE)) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE), 99)
                    return@Runnable
                }
            } else {
                if (ContextCompat.checkSelfPermission(activity, (Manifest.permission.RECORD_AUDIO)) != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(activity, (Manifest.permission.CAMERA)) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 99)
                    return@Runnable
                }
            }
            callback.onRecordStart(recordIconStatus == AUDIO)
            upBeforeGrant = false
            post(checkReadyRunnable)
            record_ib.parent.requestDisallowInterceptTouchEvent(true)
        }
    }

    private val checkReadyRunnable: Runnable by lazy {
        Runnable {
            if (callback.isReady()) {
                if (upBeforeGrant) {
                    upBeforeGrant = false
                    return@Runnable
                }
                isRecording = true
                checkRecordIb()
                updateRecordCircleAndSendIcon()
                record_circle.setLockTranslation(10000f)
            } else {
                postDelayed(checkReadyRunnable, 50)
            }
        }
    }

    private val chatSlideCallback = object : SlidePanelView.Callback {
        override fun onTimeout() {
            handleCancelOrEnd(false)
        }

        override fun onCancel() {
            handleCancelOrEnd(true)
        }
    }

    private val recordCircleCallback = object : RecordCircleView.Callback {
        override fun onSend() {
            handleCancelOrEnd(false)
        }

        override fun onCancel() {
            handleCancelOrEnd(true)
        }
    }

    interface Callback {
        fun isReady(): Boolean
        fun onRecordStart(audio: Boolean)
        fun onRecordCancel()
        fun onRecordEnd()
    }
}