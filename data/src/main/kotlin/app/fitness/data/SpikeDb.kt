package app.fitness.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** Phase 0 spike: a table holding one opaque blob per row, to find where Android's row-size limit bites. */
@Entity(tableName = "stream_blob")
class StreamBlob(@PrimaryKey val fileHash: String, val data: ByteArray)

@Dao
interface StreamBlobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(blob: StreamBlob)

    @Query("SELECT data FROM stream_blob WHERE fileHash = :hash")
    suspend fun read(hash: String): ByteArray?

    @Query("SELECT length(data) FROM stream_blob WHERE fileHash = :hash")
    suspend fun size(hash: String): Long?
}

@Database(entities = [StreamBlob::class], version = 1, exportSchema = true)
abstract class SpikeDb : RoomDatabase() {
    abstract fun blobs(): StreamBlobDao
}
