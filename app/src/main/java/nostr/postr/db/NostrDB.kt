package nostr.postr.db

import android.content.Context
import androidx.room.*


@Database(entities = [FeedItem::class, UserProfile::class], version = 1)
abstract class NostrDB : RoomDatabase() {


    abstract fun feedDao(): FeedDao
    abstract fun profileDao(): UserProfileDao

    //


    //
    companion object{
        private  var appDatabase: NostrDB?=null
         fun getDatabase(context: Context): NostrDB {
            if (appDatabase == null) {
                appDatabase = Room.databaseBuilder(context, NostrDB::class.java, "nor3.db")
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
            }
            return appDatabase!!
        }
    }



}

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed_info ORDER BY created_at DESC LIMIT 100")
    suspend fun getAll(): List<FeedItem>
    @Query("SELECT * FROM feed_info ORDER BY created_at DESC LIMIT 1")
    suspend fun getLast():FeedItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeed(feed: FeedItem)

    @Query("SELECT COUNT(*)  FROM feed_info ")
    suspend fun getCount():Int

    @Delete
    suspend fun delete(feed: FeedItem):Int

}


@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profile")
    suspend fun getAll(): List<UserProfile>

    @Query("select * from user_profile where pubkey =:s")
    suspend fun getUserInfo(s: String): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserProfile)

}









