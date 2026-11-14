/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui.scene.download.part

import android.annotation.SuppressLint
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.callBack.SpiderInfoReadCallBack
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener.PageChangeCallback
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.sxj.paginationlib.PaginationIndicator

/**
 * 下载列表分页与阅读进度。
 */
class DownloadPaginationController(private val mHost: Host) {
    interface Host {
        val list: MutableList<DownloadInfo>?

        val notifyAdapter: RecyclerView.Adapter<*>?

        val recyclerView: MyEasyRecyclerView?
    }

    @JvmField
    var indexPage: Int = 1
    @JvmField
    var pageSize: Int = 1
    var isCanPagination: Boolean = true
    @JvmField
    val paginationSize: Int = 500
    @JvmField
    val perPageCountChoices: IntArray = intArrayOf(50, 100, 200, 300, 500)

    private var myPageChangeListener: MyPageChangeListener? = null
    var paginationIndicator: PaginationIndicator? = null

    val spiderInfoMap: MutableMap<Long?, SpiderInfo?> = HashMap<Long?, SpiderInfo?>()

    private var doNotScroll = false
    var isNeedInitPage: Boolean = false
    private var needInitPageSize = false
    private var spiderInfoRequestInFlight = false
    private var spiderInfoRefreshPending = false

    fun bindPageChangeListener(
        adapter: RecyclerView.Adapter<*>?,
        recyclerView: MyEasyRecyclerView?
    ) {
        myPageChangeListener = MyPageChangeListener(
            indexPage, pageSize,
            this.isNeedInitPage, doNotScroll, adapter, recyclerView
        )
        myPageChangeListener!!.pageChangeCallback = object : PageChangeCallback {
            override fun onPageChanged(newIndexPage: Int) {
                indexPage = newIndexPage
                queryUnreadSpiderInfo()
            }

            override fun onPageSizeChanged(newPageSize: Int) {
                pageSize = newPageSize
                queryUnreadSpiderInfo()
            }
        }
    }

    fun positionInList(position: Int): Int = DownloadPaginationPolicy.positionInList(
        position, mHost.list?.size ?: 0, pageSize, indexPage, isCanPagination
    )

    fun listIndexInPage(position: Int): Int = DownloadPaginationPolicy.indexInPage(
        position, mHost.list?.size ?: 0, pageSize, indexPage, isCanPagination
    )

    fun normalizePage() {
        pageSize = pageSize.coerceAtLeast(1)
        indexPage = DownloadPaginationPolicy.clampPage(
            mHost.list?.size ?: 0, pageSize, indexPage, isCanPagination
        )
        myPageChangeListener?.indexPage = indexPage
        myPageChangeListener?.pageSize = pageSize
    }

    fun updatePaginationIndicator() {
        normalizePage()
        val list = mHost.list
        if (this.paginationIndicator == null || list == null) {
            return
        }
        if (!DownloadPaginationPolicy.isPaged(list.size, isCanPagination)) {
            paginationIndicator!!.visibility = View.GONE
            return
        }
        paginationIndicator!!.visibility = View.VISIBLE
        needInitPageSize = true
        paginationIndicator!!.initPaginationIndicator(
            pageSize,
            perPageCountChoices,
            list.size,
            indexPage
        )
        //        mPaginationIndicator.setTotalCount();
        paginationIndicator!!.setListener(myPageChangeListener)

        // 同步分页监听器的状态
        if (myPageChangeListener != null) {
            myPageChangeListener!!.indexPage = indexPage
            myPageChangeListener!!.pageSize = pageSize
            myPageChangeListener!!.isNeedInitPage = this.isNeedInitPage
            myPageChangeListener!!.isDoNotScroll = doNotScroll
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateReadProcess(result: ActivityResult) {
        if (result.resultCode == DownloadsScene.LOCAL_GALLERY_INFO_CHANGE) {
            val data = result.data
            if (data != null) {
                val info = data.getParcelableExtra<GalleryInfo?>("info")

                // Check if this is an imported archive - skip SpiderInfo processing
                var isImportedArchive = false
                if (info is DownloadInfo) {
                    isImportedArchive = info.archiveUri != null &&
                            info.archiveUri.startsWith("content://")
                }

                if (!isImportedArchive && info != null) {
                    // Only process SpiderInfo for regular downloads, not imported archives
                    spiderInfoMap.remove(info.gid)
                    val spiderInfo = SpiderInfo.getSpiderInfo(info)
                    if (spiderInfo != null) {
                        spiderInfoMap[info.gid] = spiderInfo
                    }
                    trimSpiderInfoMapToCurrentPage()
                }

                //                mSpiderInfoMap.remove(info.gid);
//                SpiderInfo spiderInfo = getSpiderInfo(info);
                var position = -1
                val list = mHost.list
                val adapter = mHost.notifyAdapter
                if (list == null || adapter == null || info == null) {
                    return
                }
                for (i in list.indices) {
                    if (list[i].gid == info.gid) {
                        position = listIndexInPage(i)
                        break
                    }
                }
                if (position >= 0) {
                    adapter.notifyItemChanged(position)
                } else {
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun resetReadingProgressInUi() {
        for (spiderInfo in spiderInfoMap.values) {
            if (spiderInfo != null) {
                spiderInfo.startPage = 0
            }
        }
        val adapter = mHost.notifyAdapter
        adapter?.notifyDataSetChanged()
    }

    val currentPageList: MutableList<DownloadInfo>
        get() {
            val list = mHost.list ?: return mutableListOf()
            val from = DownloadPaginationPolicy.pageStart(
                list.size, pageSize, indexPage, isCanPagination
            )
            val count = DownloadPaginationPolicy.itemCount(
                list.size, pageSize, indexPage, isCanPagination
            )
            return ArrayList(list.subList(from, from + count))
        }

    fun trimSpiderInfoMapToCurrentPage() {
        val pageList = this.currentPageList
        val keep: MutableSet<Long?> = HashSet<Long?>(pageList.size)
        for (info in pageList) {
            keep.add(info.gid)
        }
        spiderInfoMap.keys.retainAll(keep)
    }

    fun queryUnreadSpiderInfo() {
        val list = mHost.list ?: return
        trimSpiderInfoMapToCurrentPage()
        val pageList = this.currentPageList
        val requestList: MutableList<DownloadInfo> = ArrayList<DownloadInfo>()
        for (i in pageList.indices) {
            val info = pageList[i]
            if (!spiderInfoMap.containsKey(info.gid) || spiderInfoMap[info.gid] == null) {
                requestList.add(info)
            }
        }
        if (requestList.isEmpty()) {
            return
        }
        fetchSpiderInfo(requestList)
    }

    fun fetchSpiderInfo(infos: MutableList<DownloadInfo>) {
        if (spiderInfoRequestInFlight) {
            spiderInfoRefreshPending = true
            return
        }
        spiderInfoRequestInFlight = true
        val executor = DownloadSpiderInfoExecutor(ArrayList(infos)) { resultMap ->
            spiderInfoRequestInFlight = false
            if (resultMap != null) spiderInfoResultCallBack(resultMap)
            if (spiderInfoRefreshPending) {
                spiderInfoRefreshPending = false
                queryUnreadSpiderInfo()
            }
        }
        executor.execute()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun spiderInfoResultCallBack(resultMap: MutableMap<Long?, SpiderInfo?>) {
        val available = mHost.list?.map { it.gid }?.toSet() ?: emptySet()
        for ((gid, info) in resultMap) {
            if (gid in available) spiderInfoMap[gid] = info
        }
        trimSpiderInfoMapToCurrentPage()
        val adapter = mHost.notifyAdapter
        if (adapter != null) {
            adapter.notifyDataSetChanged()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun initPage(position: Int) {
        val list = mHost.list
        if (list != null && DownloadPaginationPolicy.isPaged(list.size, isCanPagination)) {
            indexPage = position / pageSize + 1
        }
        normalizePage()
        doNotScroll = true
        if (this.paginationIndicator != null) {
            paginationIndicator!!.skip2Pos(indexPage)
        }
        val recyclerView = mHost.recyclerView
        val pagePosition = listIndexInPage(position)
        if (pagePosition >= 0) recyclerView?.scrollToPosition(pagePosition)
    }

    fun getPageSizePos(pageSize: Int): Int {
        var index = 0
        for (i in perPageCountChoices.indices) {
            if (pageSize == perPageCountChoices[i]) {
                index = i
                break
            }
        }
        return index
    }
}
