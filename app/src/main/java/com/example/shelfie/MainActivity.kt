package com.example.shelfie

import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.TextView
import kotlin.math.abs
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class MainActivity : AppCompatActivity() {
    // View declarations
    private lateinit var bookImageView: ImageView
    private lateinit var heartButton: ImageButton
    private lateinit var recycleButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var homeButton: ImageButton
    private lateinit var profileButton: ImageButton
    private lateinit var gestureDetector: GestureDetector
    private lateinit var bookDescription: TextView
    private lateinit var prefsManager: SharedPreferencesManager

    // State management
    private var isLoading = false
    private var currentBookIndex = 0

    // Synchronized collections for thread safety
    private val bookCovers = Collections.synchronizedList(mutableListOf<Drawable>())
    private val bookReturn = Collections.synchronizedList(mutableListOf<Book>())
    private val bookQueue = Collections.synchronizedList(mutableListOf<Book>())
    private val likedBooks = Collections.synchronizedList(mutableListOf<Book>())
    private val dislikedBooks = Collections.synchronizedList(mutableListOf<Book>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = SharedPreferencesManager(this)
        initializeViews()
        setupGestureDetector()
        setupClickListeners()
        loadInitialBooks()
    }

    private fun initializeViews() {
        // Initialize views
        bookImageView = findViewById(R.id.bookImage)
        heartButton = findViewById(R.id.heartButton)
        recycleButton = findViewById(R.id.recycleButton)
        titleText = findViewById(R.id.bookTitle)
        homeButton = findViewById(R.id.homeButton)
        profileButton = findViewById(R.id.profileButton)
        bookDescription = findViewById(R.id.bookDescription)

        // Setup button appearances
        heartButton.apply {
            setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_light))
            background = ContextCompat.getDrawable(context, R.drawable.ripple_background)
        }

        recycleButton.apply {
            setColorFilter(ContextCompat.getColor(context, R.color.dark_green))
            background = ContextCompat.getDrawable(context, R.drawable.ripple_background)
        }

        homeButton.apply {
            setColorFilter(ContextCompat.getColor(context, android.R.color.black))
            background = ContextCompat.getDrawable(context, R.drawable.ripple_background)
        }

        profileButton.apply {
            setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
            background = ContextCompat.getDrawable(context, R.drawable.ripple_background)
        }

        bookImageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupClickListeners() {
        heartButton.setOnClickListener { animateAndLike() }
        recycleButton.setOnClickListener { animateAndSkip() }

        homeButton.setOnClickListener {
            homeButton.setColorFilter(ContextCompat.getColor(this, android.R.color.black))
            profileButton.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
            Toast.makeText(this, "Home", Toast.LENGTH_SHORT).show()
        }

        profileButton.setOnClickListener {
            val intent = Intent(this, FavoritesActivity::class.java)
            startActivity(intent)
            profileButton.setColorFilter(ContextCompat.getColor(this, android.R.color.black))
            homeButton.setColorFilter(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val diffX = e2.x - (e1?.x ?: 0f)
                val diffY = e2.y - (e1?.y ?: 0f)

                if (abs(diffX) > abs(diffY) &&
                    abs(diffX) > SWIPE_THRESHOLD &&
                    abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) animateAndLike() else animateAndSkip()
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showBookDetails()
                return true
            }
        })
    }

    private fun loadInitialBooks() {
        if (isLoading) return
        isLoading = true

        lifecycleScope.launch {
            try {
                getRecommendations(likedBooks.toTypedArray(), dislikedBooks.toTypedArray())
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading books: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun getRecommendations(liked: Array<Book>, disliked: Array<Book>) {
        val apiRecFetch = APIRecFetch(this)
        val shownBooks = prefsManager.getShownBooks() // Get list of previously shown books

        apiRecFetch.getBookRecs(liked, disliked, object : APIRecFetch.BookRecommendationCallback {
            override fun onSuccess(books: List<Book>) {
                lifecycleScope.launch {
                    // Filter out any books that have been shown before
                    val newBooks = books.filterNot { newBook ->
                        shownBooks.any { it.title == newBook.title }
                    }

                    if (newBooks.isEmpty()) {
                        // If all books have been shown, clear history and try again
                        prefsManager.clearShownBooks()
                        getRecommendations(liked, disliked)
                        return@launch
                    }

                    bookReturn.addAll(newBooks)
                    loadQueue()
                }
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
            }
        })
    }


    private suspend fun loadQueue() = withContext(Dispatchers.IO) {
        try {
            for (book in bookReturn.toList()) {  // Create a copy to avoid concurrent modification
                val coverImageUrl = loadBookCover(book.title)
                if (coverImageUrl != null) {
                    val coverDrawable = fetchDrawableFromUrl(coverImageUrl)
                    if (coverDrawable != null) {
                        withContext(Dispatchers.Main) {
                            bookCovers.add(coverDrawable)
                            // Create a new book with the cover URL
                            val bookWithCover = book.copy(coverUrl = coverImageUrl)
                            bookQueue.add(bookWithCover)
                            bookReturn.remove(book)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (bookCovers.isNotEmpty()) {
                    loadCurrentBook()
                } else {
                    Toast.makeText(this@MainActivity, "No book covers could be loaded", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Error loading queue: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchDrawableFromUrl(url: String): Drawable? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            BitmapDrawable(resources, bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun loadBookCover(title: String): String? {
        return CoverJPG().searchBookByTitle(title)
    }

    private fun loadCurrentBook() {
        if (bookQueue.isEmpty() || bookCovers.isEmpty()) {
            loadInitialBooks()
            return
        }

        if (currentBookIndex >= bookQueue.size) {
            currentBookIndex = 0
        }

        try {
            bookImageView.setImageDrawable(bookCovers[currentBookIndex])
            titleText.text = bookQueue[currentBookIndex].title
            bookDescription.text = bookQueue[currentBookIndex].description
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading book: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun animateAndLike() {
        animateSwipe(true) { likeCurrentItem() }
    }

    private fun animateAndSkip() {
        animateSwipe(false) { skipCurrentItem() }
    }

    private fun animateSwipe(isLike: Boolean, onComplete: () -> Unit) {
        val animation = TranslateAnimation(
            0f,
            if (isLike) bookImageView.width.toFloat() else -bookImageView.width.toFloat(),
            0f,
            0f
        ).apply {
            duration = 300
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    heartButton.isEnabled = false
                    recycleButton.isEnabled = false
                }
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    bookImageView.translationX = 0f
                    onComplete()
                    heartButton.isEnabled = true
                    recycleButton.isEnabled = true
                }
            })
        }
        bookImageView.startAnimation(animation)
    }

    private fun likeCurrentItem() {
        if (currentBookIndex < bookQueue.size) {
            val book = bookQueue[currentBookIndex]
            likedBooks.add(book)
            prefsManager.saveLikedBook(book)
            prefsManager.addShownBook(book)
            loadNextBook()
        }
    }
    private fun skipCurrentItem() {
        if (currentBookIndex < bookQueue.size) {
            bookQueue[currentBookIndex].let { book ->
                dislikedBooks.add(book)
                prefsManager.addShownBook(book) // Mark as shown
                loadNextBook()
            }
        }
    }
    private fun loadNextBook() {
        currentBookIndex++
        if (currentBookIndex >= bookQueue.size - 2) {
            loadInitialBooks()
        }
        loadCurrentBook()
    }

    private fun showBookDetails() {
        if (currentBookIndex < bookQueue.size) {
            Toast.makeText(this, "Showing details for: ${bookQueue[currentBookIndex].title}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bookCovers.clear()
        bookQueue.clear()
        bookReturn.clear()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}