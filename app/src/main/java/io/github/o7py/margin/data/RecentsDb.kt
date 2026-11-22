package io.github.o7py.margin.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Recents & per-document state (SPEC §9.5). */
@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val pageCount: Int,
    val lastOpened: Long,
    val lastPage: Int = 0,
    val zoom: Float = 1f,
    val mode: String = "WRITING",
    val thumbnailPath: String? = null,
    val hasText: Boolean = false,
    /** Offset into the last page (fraction of page height) for exact scroll restore. */
    val pageOffset: Float = 0f,
)

@Dao
interface RecentsDao {
    @Query("SELECT * FROM recents ORDER BY lastOpened DESC")
    fun observe(): Flow<List<RecentEntity>>

    @Query("SELECT * FROM recents WHERE uri = :uri")
    suspend fun get(uri: String): RecentEntity?

    @Upsert
    suspend fun upsert(e: RecentEntity)

    @Query("DELETE FROM recents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("UPDATE recents SET lastPage = :page, pageOffset = :offset, zoom = :zoom WHERE uri = :uri")
    suspend fun updatePosition(uri: String, page: Int, offset: Float, zoom: Float)

    @Query("UPDATE recents SET mode = :mode WHERE uri = :uri")
    suspend fun updateMode(uri: String, mode: String)

    @Query("UPDATE recents SET hasText = :hasText WHERE uri = :uri")
    suspend fun updateHasText(uri: String, hasText: Boolean)
}

@Database(entities = [RecentEntity::class], version = 1, exportSchema = true)
abstract class MarginDb : RoomDatabase() {
    abstract fun recents(): RecentsDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, MarginDb::class.java, "margin.db").build()
    }
}
