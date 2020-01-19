package io.github.droidkaigi.confsched2020.session.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.observe
import androidx.navigation.NavOptions
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.TransitionManager
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import coil.Coil
import coil.api.load
import coil.transform.CircleCropTransformation
import com.google.android.material.chip.Chip
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.databinding.ViewHolder
import dagger.Module
import dagger.Provides
import io.github.droidkaigi.confsched2020.di.PageScope
import io.github.droidkaigi.confsched2020.ext.assistedActivityViewModels
import io.github.droidkaigi.confsched2020.ext.assistedViewModels
import io.github.droidkaigi.confsched2020.ext.getThemeColor
import io.github.droidkaigi.confsched2020.model.Session
import io.github.droidkaigi.confsched2020.model.Speaker
import io.github.droidkaigi.confsched2020.model.SpeechSession
import io.github.droidkaigi.confsched2020.model.defaultLang
import io.github.droidkaigi.confsched2020.model.defaultTimeZoneOffset
import io.github.droidkaigi.confsched2020.session.R
import io.github.droidkaigi.confsched2020.session.databinding.FragmentSessionDetailBinding
import io.github.droidkaigi.confsched2020.session.databinding.FragmentSessionDetailWipBinding
import io.github.droidkaigi.confsched2020.session.ui.SessionDetailFragmentDirections.Companion.actionSessionToSpeaker
import io.github.droidkaigi.confsched2020.session.ui.SessionDetailFragmentDirections.Companion.actionSessionToSurvey
import io.github.droidkaigi.confsched2020.session.ui.item.SessionDetailDescriptionItem
import io.github.droidkaigi.confsched2020.session.ui.item.SessionDetailSpeakerItem
import io.github.droidkaigi.confsched2020.session.ui.item.SessionDetailSpeakerSubtitleItem
import io.github.droidkaigi.confsched2020.session.ui.item.SessionDetailTargetItem
import io.github.droidkaigi.confsched2020.session.ui.item.SessionDetailTitleItem
import io.github.droidkaigi.confsched2020.session.ui.item.SessionItem
import io.github.droidkaigi.confsched2020.session.ui.viewmodel.SessionDetailViewModel
import io.github.droidkaigi.confsched2020.session.ui.widget.SessionDetailItemDecoration
import io.github.droidkaigi.confsched2020.system.ui.viewmodel.SystemViewModel
import io.github.droidkaigi.confsched2020.util.DaggerFragment
import io.github.droidkaigi.confsched2020.util.ProgressTimeLatch
import io.github.droidkaigi.confsched2020.util.autoCleared
import javax.inject.Inject
import javax.inject.Provider

private const val ELLIPSIS_LINE_COUNT = 6

class SessionDetailFragment : DaggerFragment(R.layout.fragment_session_detail_wip) {

    private var binding: FragmentSessionDetailWipBinding by autoCleared()

    @Inject lateinit var systemViewModelFactory: Provider<SystemViewModel>
    private val systemViewModel by assistedActivityViewModels {
        systemViewModelFactory.get()
    }
    @Inject lateinit var sessionDetailViewModelFactory: SessionDetailViewModel.Factory
    private val sessionDetailViewModel by assistedViewModels {
        sessionDetailViewModelFactory.create(navArgs.sessionId)
    }

    private val navArgs: SessionDetailFragmentArgs by navArgs()
    @Inject lateinit var sessionItemFactory: SessionItem.Factory

    private var progressTimeLatch: ProgressTimeLatch by autoCleared()
    private var showEllipsis = true

    companion object {
        const val TRANSITION_NAME_SUFFIX = "detail"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentSessionDetailWipBinding.bind(view)

        progressTimeLatch = ProgressTimeLatch { showProgress ->
            binding.progressBar.isVisible = showProgress
        }.apply {
            loading = true
        }

        sessionDetailViewModel.uiModel
            .observe(viewLifecycleOwner) { uiModel: SessionDetailViewModel.UiModel ->
                uiModel.error?.let { systemViewModel.onError(it) }
                progressTimeLatch.loading = uiModel.isLoading
                uiModel.session
                    ?.let { session -> setupSessionViews(session) }
            }

        binding.bottomAppBar.setOnMenuItemClickListener {
            handleNavigation(it.itemId)
        }
    }

    private fun handleNavigation(@IdRes itemId: Int): Boolean {
        val navController = findNavController()
        return try {
            // ignore if current destination is selected
            if (navController.currentDestination?.id == itemId) return false
            val builder = NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.fade_out)
            val options = builder.build()
            navController.navigate(itemId, null, options)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun setupSessionViews(session: Session) {
        if (binding.sessionDetailRecycler.adapter == null) {
            context?.let {
                val adapter = GroupAdapter<ViewHolder<*>>()
                binding.sessionDetailRecycler.adapter = adapter
                binding.sessionDetailRecycler.layoutManager = LinearLayoutManager(it)
                binding.sessionDetailRecycler.addItemDecoration(
                    SessionDetailItemDecoration(
                        adapter,
                        it
                    )
                )
                adapter.add(SessionDetailTitleItem(it, session))
                adapter.add(SessionDetailDescriptionItem(it, session))
                if (session.hasIntendedAudience)
                    adapter.add(SessionDetailTargetItem(session))
                if (session.hasSpeaker) {
                    adapter.add(SessionDetailSpeakerSubtitleItem())
                    var firstSpeaker = true
                    (session as? SpeechSession)?.speakers.orEmpty().indices.forEach { index ->
                        val speaker: Speaker =
                            (session as? SpeechSession)?.speakers?.getOrNull(index)
                                ?: return@forEach
                        adapter.add(
                            SessionDetailSpeakerItem(
                                viewLifecycleOwner,
                                speaker,
                                firstSpeaker
                            )
                        )
                        firstSpeaker = false
                    }
                }
            }
        }
        binding.sessionFavorite.setOnClickListener {
            sessionDetailViewModel.favorite(session)
        }
//        binding.survey.setOnClickListener {
//            findNavController().navigate(actionSessionToSurvey(session.id))
//        }
        binding.session = session
//        setupSessionDescription(session.desc)
//        binding.speechSession = (session as? SpeechSession)
//        binding.lang = defaultLang()
//        binding.time.text = session.timeSummary(defaultLang(), defaultTimeZoneOffset())
//        if (session is SpeechSession) {
//            val langLabel = session.lang.text.getByLang(defaultLang())
//            val categoryLabel = session.category.name.getByLang(defaultLang())
//            val newTag = "$categoryLabel:$categoryLabel"
//            val savedTag = binding.tags.tag
//            if (savedTag != newTag) {
//                binding.tags.removeAllViews()
//                binding.tags.addView(Chip(context).apply {
//                    text = categoryLabel
//                    isClickable = false
//                })
//                binding.tags.addView(Chip(context).apply {
//                    text = langLabel
//                    isClickable = false
//                })
//                binding.tags.tag = newTag
//            }
//        }
//        binding.speakers.bindSpeaker(session)
    }

    private fun setupSessionDescription(fullDescription: String) {
//        val textView = binding.sessionDescription
//        textView.doOnPreDraw {
//            textView.text = fullDescription
//            //Return here if not more than the specified number of rows
//            if (!(textView.lineCount > ELLIPSIS_LINE_COUNT && showEllipsis)) return@doOnPreDraw
//            val lastLineStartPosition = textView.layout.getLineStart(ELLIPSIS_LINE_COUNT - 1)
//            val ellipsis = getString(R.string.ellipsis_label)
//            val lastLineText = TextUtils.ellipsize(
//                fullDescription.substring(lastLineStartPosition),
//                textView.paint,
//                textView.width - textView.paint.measureText(ellipsis),
//                TextUtils.TruncateAt.END
//            )
//            val ellipsisColor = ContextCompat.getColor(requireContext(), R.color.design_default_color_secondary)
//            val onClickListener = {
//                TransitionManager.beginDelayedTransition(binding.sessionLayout)
//                textView.text = fullDescription
//                showEllipsis = !showEllipsis
//            }
//            val detailText = fullDescription.substring(0, lastLineStartPosition) + lastLineText
//            val text = buildSpannedString {
//                clickableSpan(onClickListener, {
//                    append(detailText)
//                    color(ellipsisColor) {
//                        append(ellipsis)
//                    }
//                })
//            }
//            textView.setText(text, TextView.BufferType.SPANNABLE)
//            textView.movementMethod = LinkMovementMethod.getInstance()
//        }
    }

    private fun SpannableStringBuilder.clickableSpan(
        clickListener: () -> Unit,
        builderAction: SpannableStringBuilder.() -> Unit
    ) {
        inSpans(object : ClickableSpan() {
            override fun onClick(widget: View) {
                clickListener()
            }

            override fun updateDrawState(ds: TextPaint) {
                // nothing
            }
        }, builderAction)
    }

    private fun ViewGroup.bindSpeaker(session: Session) {
        removeAllViews()
        (session as? SpeechSession)?.speakers.orEmpty().indices.forEach { index ->
            val speaker: Speaker =
                (session as? SpeechSession)?.speakers?.getOrNull(index) ?: return@forEach
            val speakerView = layoutInflater.inflate(
                R.layout.layout_speaker_session_detail, this, false
            ) as ViewGroup
            val speakerNameView = speakerView.findViewById<TextView>(R.id.speaker)
            val speakerImageView = speakerView.findViewById<ImageView>(R.id.speaker_image)
            speakerImageView.transitionName = "${speaker.id}-${TRANSITION_NAME_SUFFIX}"
            speakerView.setOnClickListener {
                val extras = FragmentNavigatorExtras(
                    speakerImageView to speakerImageView.transitionName
                )
                findNavController()
                    .navigate(actionSessionToSpeaker(speaker.id, TRANSITION_NAME_SUFFIX), extras)
            }
            bindSpeakerData(speaker, speakerNameView, speakerImageView)
            addView(speakerView)
        }
    }

    private fun bindSpeakerData(
        speaker: Speaker,
        speakerNameView: TextView,
        speakerImageView: ImageView
    ) {
        speakerNameView.text = speaker.name
//        setHighlightText(textView, query)
        val imageUrl = speaker.imageUrl
        val context = speakerNameView.context
        val placeHolder = run {
            VectorDrawableCompat.create(
                context.resources,
                R.drawable.ic_person_outline_black_32dp,
                null
            )?.apply {
                setTint(
                    context.getThemeColor(R.attr.colorOnBackground)
                )
            }
        }?.also {
            speakerImageView.setImageDrawable(it)
        }

        Coil.load(context, imageUrl) {
            crossfade(true)
            placeholder(placeHolder)
            transformations(CircleCropTransformation())
            lifecycle(viewLifecycleOwner)
            target {
                speakerImageView.setImageDrawable(it)
            }
        }
    }
}

@Module
abstract class SessionDetailFragmentModule {
    @Module
    companion object {
        @PageScope
        @JvmStatic @Provides fun providesLifecycleOwnerLiveData(
            sessionDetailFragment: SessionDetailFragment
        ): LiveData<LifecycleOwner> {
            return sessionDetailFragment.viewLifecycleOwnerLiveData
        }
    }
}
