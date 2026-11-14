package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.userTag.TagPushParam
import com.hippo.ehviewer.client.data.userTag.UserTagList
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.util.TagTranslationUtil
import com.hippo.ehviewer.subscription.LocalFollowRepository
import com.hippo.ehviewer.subscription.LocalUpdateService
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.SceneFragment

class GalleryListSceneDialog(val baseScene: BaseScene) {
    val context: Context? = baseScene.context
    private var tagName: String? = null

    fun setTagName(tagName: String?) {
        this.tagName = tagName
    }

    fun showTagLongPressDialog(ehTags: EhTagDatabase?) {
        val temp: String?
        val index = tagName!!.indexOf(':')
        temp = if (index >= 0) {
            tagName!!.substring(index + 1)
        } else {
            tagName
        }
        val title = if (Settings.getShowTagTranslations()) {
            TagTranslationUtil.getTagCN(tagName, ehTags) + "(" + tagName + ")"
        } else {
            tagName
        }
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_tag_actions, null)
        val builder = AlertDialog.Builder(context!!)
            .setTitle(title)
            .setView(content)
        if (!Settings.isLogin()) {
            content.findViewById<View>(R.id.tag_action_exclude).visibility = View.GONE
            content.findViewById<View>(R.id.tag_action_subscribe).visibility = View.GONE
        }
        val dialog = builder.create()
        content.findViewById<android.widget.Button>(R.id.tag_action_copy).setOnClickListener {
            dialog.dismiss()
            copyTag(tagName)
        }
        content.findViewById<android.widget.Button>(R.id.tag_action_subscribe).setOnClickListener {
            dialog.dismiss()
            requestTag(tagName, true)
        }
        content.findViewById<android.widget.Button>(R.id.tag_action_follow).apply {
            setText(
                if (LocalFollowRepository.getInstance().contains(tagName))
                    R.string.local_unfollow else R.string.local_follow
            )
            setOnClickListener {
                dialog.dismiss()
                toggleLocalFollow()
            }
        }
        content.findViewById<View>(R.id.tag_action_definition).setOnClickListener {
            dialog.dismiss()
            UrlOpener.openUrl(context, EhUrl.getTagDefinitionUrl(temp), false)
        }
        content.findViewById<View>(R.id.tag_action_filter).setOnClickListener {
            dialog.dismiss()
            showFilterTagDialog()
        }
        content.findViewById<View>(R.id.tag_action_exclude).setOnClickListener {
            dialog.dismiss()
            requestTag(tagName, false)
        }
        dialog.show()
    }

    private fun toggleLocalFollow() {
        val tag = tagName ?: return
        val repository = LocalFollowRepository.getInstance()
        val message: Int
        if (repository.contains(tag)) {
            repository.remove(tag)
            message = R.string.local_follow_removed
        } else if (repository.add(tag)) {
            LocalUpdateService.startPendingBaselines(context!!)
            message = R.string.local_follow_added
        } else {
            message = R.string.local_follow_already
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (baseScene is GalleryDetailScene) {
            baseScene.refreshLocalFollowHighlights()
        }
    }

    private fun showFilterTagDialog() {
        if (context == null) {
            return
        }

        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.filter_the_tag, tagName))
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, which: Int ->
                if (which != DialogInterface.BUTTON_POSITIVE) {
                    return@setPositiveButton
                }
                val filter = Filter()
                filter.mode = EhFilter.MODE_TAG
                filter.text = tagName
                EhFilter.getInstance().addFilter(filter)
                showTip(R.string.filter_added, BaseScene.LENGTH_SHORT)
            }.show()
    }

    private fun showTip(@StringRes id: Int, length: Int) {
        val activity = baseScene.activity
        if (activity is MainActivity) {
            activity.showTip(id, length)
        }
    }

    private fun copyTag(tag: String?) {
        val manager = context!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(null, tag))
        Toast.makeText(context, R.string.gallery_tag_copy, Toast.LENGTH_LONG).show()
    }

    private fun requestTag(tagName: String?, tagState: Boolean) {
        val url = EhUrl.getMyTag()

        if (null == context) {
            return
        }
        val activity = baseScene.activity2 ?: return

        val callback =
            SubscriptionDetailListener(context, activity.stageId, baseScene.tag, tagState)

        val param = TagPushParam()

        param.tagNameNew = tagName
        if (tagState) {
            param.tagWatchNew = "on"
        } else {
            param.tagHiddenNew = "on"
        }


        val mRequest = EhRequest()
            .setMethod(EhClient.METHOD_ADD_TAG)
            .setArgs(url, param).setCallback(callback)

        EhApplication.getEhClient(context).execute(mRequest)
    }

    private inner class SubscriptionDetailListener(
        context: Context,
        stageId: Int,
        sceneTag: String?,
        private val tagState: Boolean
    ) :
        EhCallback<GalleryListScene?, UserTagList?>(context, stageId, sceneTag) {
        override fun isInstance(scene: SceneFragment): Boolean {
            return false
        }

        override fun onSuccess(result: UserTagList?) {
            if (result == null) {
                Toast.makeText(context, R.string.subscription_tag_update_failed, Toast.LENGTH_SHORT)
                    .show()
                return
            }
            EhApplication.saveUserTagList(context!!, result)
            com.hippo.ehviewer.subscription.SubscriptionSnapshot.replace(result)
            baseScene.setTagList(result)
            val state =
                if (tagState) context!!.getString(R.string.subscription_watched) else context!!.getString(
                    R.string.subscription_hidden
                )
            val msg = context.getString(R.string.subscription_success, state)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        override fun onFailure(e: Exception) {
            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
        }

        override fun onCancel() {
        }
    }
}
