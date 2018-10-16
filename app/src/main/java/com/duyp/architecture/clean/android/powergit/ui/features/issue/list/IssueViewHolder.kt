package com.duyp.architecture.clean.android.powergit.ui.features.issue.list

import android.graphics.Color
import android.support.v7.widget.AppCompatImageView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.duyp.architecture.clean.android.powergit.R
import com.duyp.architecture.clean.android.powergit.domain.entities.IssueEntity
import com.duyp.architecture.clean.android.powergit.domain.entities.type.IssueState
import com.duyp.architecture.clean.android.powergit.inflate
import com.duyp.architecture.clean.android.powergit.ui.base.adapter.BaseViewHolder
import com.duyp.architecture.clean.android.powergit.ui.helper.PullsIssuesParser
import com.duyp.architecture.clean.android.powergit.ui.utils.AvatarLoader
import com.duyp.architecture.clean.android.powergit.ui.utils.ParseDateFormat
import com.duyp.architecture.clean.android.powergit.ui.widgets.SpannableBuilder

/**
 * Created by duypham on 10/30/17.
 *
 */

class IssueViewHolder private constructor(
        itemView: View, 
        private val avatarLoader: AvatarLoader, 
        private val withAvatar: Boolean,
        private val showRepoName: Boolean,
        private val showState: Boolean = false
) : BaseViewHolder<IssueEntity>(itemView) {

    private var avatarLayout: ImageView? = itemView.findViewById(R.id.avatarLayout)
    private var issueState: AppCompatImageView? = itemView.findViewById(R.id.issue_state)
    
    private var title: TextView = itemView.findViewById(R.id.title)
    private var details: TextView = itemView.findViewById(R.id.details)
    private var commentsNo: TextView = itemView.findViewById(R.id.commentsNo)
    private var labelContainer: LinearLayout = itemView.findViewById(R.id.labelContainer)

    private var defaultMargin = 10

    override fun bindData(data: IssueEntity) {
        title.text = data.title
        if (data.state != null) {
            val builder = SpannableBuilder.builder()
            if (showRepoName && data.htmlUrl != null) {
                val parser = PullsIssuesParser.getForIssue(data.htmlUrl!!)
                if (parser != null)
                    builder.bold(parser.login)
                            .append("/")
                            .bold(parser.repoId)
                            .bold("#")
                            .bold(data.number.toString()).append(" ")
                            .append(" ")
            }
            if (!showRepoName) {
                if (data.state == IssueState.CLOSED) {
                    if (data.closedBy == null) {
                        builder.bold("#")
                                .bold(data.number.toString()).append(" ")
                                .append(" ")
                    } else {
                        builder.append("#")
                                .append(data.number.toString()).append(" ")
                                .append(data.closedBy!!.login)
                                .append(" ")
                    }
                } else {
                    builder.bold("#")
                            .bold(data.number.toString()).append(" ")
                            .append(data.user!!.login)
                            .append(" ")
                }
            }
            val time = ParseDateFormat.getTimeAgo(if (data.state == IssueState.OPEN)
                data.createdAt
            else
                data.closedAt)
            details.text = builder
                    .append(data.state)
                    .append(" ")
                    .append(time)
            if (data.comments > 0) {
                commentsNo.text = data.comments.toString()
                commentsNo.visibility = View.VISIBLE
            } else {
                commentsNo.visibility = View.GONE
            }
        }
        if (showState) {
            issueState!!.visibility = View.VISIBLE
            issueState!!.setImageResource(if (data.state == IssueState.OPEN)
                R.drawable.ic_issue_opened_small
            else
                R.drawable.ic_issue_closed_small)
        } else {
            issueState!!.visibility = View.GONE
        }
        if (withAvatar && avatarLayout != null && data.user != null) {
            avatarLoader.loadImage<String>(data.user!!.avatarUrl, avatarLayout)
            avatarLayout!!.visibility = View.VISIBLE
        }

        labelContainer.removeAllViews()
        labelContainer.visibility = if (data.labels!!.isEmpty()) View.GONE else View.VISIBLE
        val context = labelContainer.context
        for ((_, _, name, color) in data.labels!!) {
            val textView = TextView(context)
            textView.text = name
            textView.setTextColor(Color.WHITE)
            textView.setBackgroundColor(Color.parseColor("#" + color!!))
            textView.setPadding(defaultMargin, defaultMargin, defaultMargin, defaultMargin)

            val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )

            labelContainer.addView(textView, params)
        }
    }

    companion object {

        @JvmOverloads
        fun newInstance(viewGroup: ViewGroup, avatarLoader: AvatarLoader, withAvatar: Boolean, showRepoName: Boolean, 
                        showState: Boolean = false): IssueViewHolder {
            return if (withAvatar) {
                IssueViewHolder(viewGroup.inflate(R.layout.item_issue), avatarLoader, true, showRepoName, showState)
            } else {
                IssueViewHolder(viewGroup.inflate(R.layout.item_issue_no_image), avatarLoader, false, showRepoName, showState)
            }
        }
    }
}