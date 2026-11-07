package app.tagledger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.UUID

object Status {
    const val DONE = "done"
    /** Read from a bank SMS and waiting for the user to confirm. */
    const val PENDING = "pending"
}

@Entity(tableName = "expenses", indices = [Index("time"), Index("ref"), Index("status")])
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val amount: Double,
    /** "expense" or "income". */
    val type: String = "expense",
    /** When the payment happened, epoch millis. */
    val time: Long,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val payee: String = "",
    /** UPI / bank reference number, used to drop duplicates. */
    val ref: String = "",
    /** Proof photo kept in app storage (filesDir/proofs). */
    val photoPath: String = "",
    /** Link to the same photo in Google Drive, once uploaded. */
    val photoUrl: String = "",
    val status: String = Status.DONE,
    /** "manual", "sms", "share" or "import". */
    val source: String = "manual",
    /** Original SMS or receipt text, shown while reviewing. */
    val raw: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val syncedAt: Long = 0,
) {
    val isIncome get() = type == "income"
    val signed get() = if (isIncome) amount else -amount
}

class Converters {
    @TypeConverter fun tagsToString(tags: List<String>): String = tags.joinToString(" ")
    @TypeConverter fun stringToTags(s: String): List<String> = s.split(' ').filter { it.isNotBlank() }
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE status = 'done' ORDER BY time DESC")
    fun observeDone(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE status = 'pending' ORDER BY time DESC")
    fun observePending(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun get(id: String): Expense?

    @Upsert suspend fun upsert(e: Expense)
    @Upsert suspend fun upsertAll(list: List<Expense>)

    @Query("DELETE FROM expenses WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM expenses WHERE ref = :ref AND ref != ''")
    suspend fun countRef(ref: String): Int

    @Query("SELECT COUNT(*) FROM expenses WHERE type = :type AND amount = :amount AND time BETWEEN :from AND :to")
    suspend fun countSimilar(type: String, amount: Double, from: Long, to: Long): Int

    @Query("SELECT * FROM expenses WHERE status = 'done' AND payee != '' AND LOWER(payee) = LOWER(:payee) ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastForPayee(payee: String): Expense?

    @Query("SELECT * FROM expenses WHERE status = 'done' AND updatedAt > syncedAt")
    suspend fun unsynced(): List<Expense>

    @Query("SELECT * FROM expenses WHERE status = 'done' AND photoPath != '' AND photoUrl = ''")
    suspend fun photosToUpload(): List<Expense>

    @Query("UPDATE expenses SET syncedAt = :at WHERE id IN (:ids) AND updatedAt <= :at")
    suspend fun markSynced(ids: List<String>, at: Long)

    @Query("UPDATE expenses SET photoUrl = :url, updatedAt = :now WHERE id = :id")
    suspend fun setPhotoUrl(id: String, url: String, now: Long = System.currentTimeMillis())
}

@Database(entities = [Expense::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun expenses(): ExpenseDao

    companion object {
        fun create(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "tagledger.db").build()
    }
}

/** Small settings store. Secret and URL never leave the phone except to your own Apps Script. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tagledger", Context.MODE_PRIVATE)
    var scriptUrl: String
        get() = sp.getString("scriptUrl", "") ?: ""
        set(v) = sp.edit().putString("scriptUrl", v).apply()
    var secret: String
        get() = sp.getString("secret", "") ?: ""
        set(v) = sp.edit().putString("secret", v).apply()
    var currency: String
        get() = sp.getString("currency", "INR") ?: "INR"
        set(v) = sp.edit().putString("currency", v).apply()
    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()
    var smsEnabled: Boolean
        get() = sp.getBoolean("smsEnabled", false)
        set(v) = sp.edit().putBoolean("smsEnabled", v).apply()
    var sheetUrl: String
        get() = sp.getString("sheetUrl", "") ?: ""
        set(v) = sp.edit().putString("sheetUrl", v).apply()
    /** Ids deleted on the phone that still need removing from the Sheet. */
    var pendingDeletes: Set<String>
        get() = sp.getStringSet("pendingDeletes", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("pendingDeletes", v).apply()
    /** Export choices kept between exports: column names in order, file format, summary sheets. */
    var exportColumns: String
        get() = sp.getString("exportColumns", "") ?: ""
        set(v) = sp.edit().putString("exportColumns", v).apply()
    var exportXlsx: Boolean
        get() = sp.getBoolean("exportXlsx", true)
        set(v) = sp.edit().putBoolean("exportXlsx", v).apply()
    var exportSummary: Boolean
        get() = sp.getBoolean("exportSummary", true)
        set(v) = sp.edit().putBoolean("exportSummary", v).apply()
    val remoteConfigured get() = scriptUrl.isNotBlank() && secret.isNotBlank()
}
