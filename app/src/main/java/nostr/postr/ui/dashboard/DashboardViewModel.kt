package nostr.postr.ui.dashboard

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nostr.postr.JsonFilter
import nostr.postr.MyApplication
import nostr.postr.core.WsViewModel
import nostr.postr.db.ChatRoom
import nostr.postr.db.NostrDB
import nostr.postr.db.UserProfile
import nostr.postr.events.ContactListEvent
import nostr.postr.events.MetadataEvent
import nostr.postr.toHex
import java.util.*

class DashboardViewModel : WsViewModel() {

    val followList = MutableLiveData<List<FollowInfo>>()

    val chatRoomLiveDat = MutableLiveData<List<ChatRoom>>()

    var mainSubscriptionId: String = "Follow_list_${getRand5()}"
    override fun onRecContactListEvent(subscriptionId: String, event: ContactListEvent) {
        super.onRecContactListEvent(subscriptionId, event)

        if (subscriptionId == mainSubscriptionId) {

            viewModelScope.launch {

                withContext(Dispatchers.IO) {

                    val noProfileList = mutableListOf<String>()

                    var list = event.follows
                        .map { f ->
                            FollowInfo(
                                f.pubKeyHex,
                                f.relayUri,
                                NostrDB.getDatabase(MyApplication._instance).profileDao()
                                    .getUserInfo2(f.pubKeyHex).also {
                                        if (it == null) {
                                            noProfileList.add(f.pubKeyHex)
                                        }
                                    }
                            )
                        }
                    followList.postValue(list)
                    reqProfile(noProfileList)
                }
            }
        }
    }

    override fun onRecMetadataEvent(subscriptionId: String, event: MetadataEvent) {
        super.onRecMetadataEvent(subscriptionId, event)
        event.contactMetaData.let {
            val userProfile = UserProfile(event.pubKey.toHex()).apply {
                this.name = it.name
                this.about = it.about
                this.picture = it.picture
                this.nip05 = it.nip05
                this.display_name = it.display_name
                this.banner = it.banner
                this.website = it.website
                this.lud16 = it.lud16
            }
            //                        Log.e("MetadataEvent--->", "---->${userProfile.name}")
            scope.launch {
                NostrDB.getDatabase(MyApplication.getInstance())
                    .profileDao().insertUser(userProfile)
            }
        }

    }


    fun subFollows(pubKey: String) {

        wsClient.value.requestAndWatch(
            mainSubscriptionId, mutableListOf(
                JsonFilter(
                    authors = mutableListOf(pubKey),
                    kinds = mutableListOf(3),
                    limit = 1
                )
            )
        )
    }

    override fun onCleared() {
        wsClient.value.close(mainSubscriptionId)
        super.onCleared()
    }

    fun loadChat() {

        comDis.add(
            NostrDB.getDatabase(MyApplication._instance)
                .chatDao().getAllChatRoom().map {
                    it.forEach {
                        it.profile = NostrDB.getDatabase(MyApplication._instance)
                            .profileDao().getUserInfo2(it.sendTo)
                    }
                    it
                }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    chatRoomLiveDat.value = it
                }
        )

    }

    private fun reqProfile(list: List<String>) {
        viewModelScope.launch {
            val filter = JsonFilter(
                kinds = mutableListOf(0),
                authors = list
            )
            val profileSubscriptionId = "profile_${UUID.randomUUID().toString().substring(0..5)}"
            wsClient.value.requestAndWatch(
                subscriptionId = profileSubscriptionId,
                filters = mutableListOf(filter)
            )
        }
    }


}