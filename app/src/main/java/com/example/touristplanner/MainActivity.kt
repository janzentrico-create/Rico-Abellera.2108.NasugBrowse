package com.example.touristplanner

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Date

// =====================================================
// 1. THEME & MODELS
// =====================================================
val PrimaryBlue = Color(0xFF1E3A8A)
val AccentOrange = Color(0xFFF59E0B)
val BgLight = Color(0xFFF8FAFC)
val TextDark = Color(0xFF1E293B)
val TextGray = Color(0xFF64748B)

val LightColors = lightColorScheme(primary = PrimaryBlue, secondary = AccentOrange, background = BgLight, surface = Color.White, onSurface = TextDark)
val DarkColors = darkColorScheme(primary = Color(0xFF60A5FA), secondary = AccentOrange, background = Color(0xFF0F172A), surface = Color(0xFF1E293B), onSurface = Color(0xFFF1F5F9))

// GLASSMORPHISM CONSTANTS
val GlassWhite = Color.White.copy(alpha = 0.65f)
val GlassDark = Color.Black.copy(alpha = 0.45f)
val GlassBorderLight = Color.White.copy(alpha = 0.8f)
val GlassBorderDark = Color.White.copy(alpha = 0.2f)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) GlassDark else GlassWhite
        ),
        border = BorderStroke(1.dp, if (isDark) GlassBorderDark else GlassBorderLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp) // Flat, relying on the border for definition
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

data class Review(val author: String, val rating: Int, val comment: String)
data class Activity(val name: String, val price: Double)
data class WeatherInfo(val temp: String, val condition: String, val icon: ImageVector)
data class ScheduledVisit(val destination: Destination, val date: Date, val time: String, val selectedActivities: List<Activity>)

abstract class Destination(
    val id: Int, val name: String, val location: String, open val baseCost: Double,
    val rating: Double, val imageResId: Int, val description: String, val latitude: Double, val longitude: Double,
    val activities: List<Activity>
) {
    open fun category(): String = "General"
    open fun icon(): ImageVector = Icons.Default.Place
}

class Beach(id: Int, name: String, location: String, baseCost: Double, rating: Double, imageResId: Int, description: String, lat: Double, lng: Double, activities: List<Activity>) : Destination(id, name, location, baseCost, rating, imageResId, description, lat, lng, activities) { override fun category() = "Beach"; override fun icon() = Icons.Default.BeachAccess }
class HistoricalSite(id: Int, name: String, location: String, baseCost: Double, rating: Double, imageResId: Int, description: String, lat: Double, lng: Double, entryFee: Double, activities: List<Activity>) : Destination(id, name, location, baseCost + entryFee, rating, imageResId, description, lat, lng, activities) { override fun category() = "Historical"; override fun icon() = Icons.Default.AccountBalance }
class AdventureSpot(id: Int, name: String, location: String, baseCost: Double, rating: Double, imageResId: Int, description: String, lat: Double, lng: Double, difficulty: String, activities: List<Activity>) : Destination(id, name, location, baseCost, rating, imageResId, description, lat, lng, activities) { override fun category() = "Adventure"; override fun icon() = Icons.Default.Terrain }
class Restaurant(id: Int, name: String, location: String, baseCost: Double, rating: Double, imageResId: Int, description: String, lat: Double, lng: Double, cuisine: String, activities: List<Activity>) : Destination(id, name, location, baseCost, rating, imageResId, description, lat, lng, activities) { override fun category() = "Food"; override fun icon() = Icons.Default.Restaurant }

class PlannerSystem(val ownerName: String, val ownerEmail: String, private val context: Context) {
    val scheduledVisits = mutableStateListOf<ScheduledVisit>()
    val favoriteDestinations = mutableStateListOf<Destination>()

    fun loadSavedState(allDestinations: List<Destination>) {
        val prefs = context.getSharedPreferences("NasugBrowseData", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("planner_data_$ownerEmail", null) ?: return
        try {
            val json = JSONObject(jsonStr)
            val favArray = json.optJSONArray("favorites")
            if (favArray != null) {
                favoriteDestinations.clear()
                for (i in 0 until favArray.length()) {
                    allDestinations.find { it.id == favArray.getInt(i) }?.let { favoriteDestinations.add(it) }
                }
            }
            val visitsArray = json.optJSONArray("visits")
            if (visitsArray != null) {
                scheduledVisits.clear()
                for (i in 0 until visitsArray.length()) {
                    val visitObj = visitsArray.getJSONObject(i)
                    val destId = visitObj.getInt("destId")
                    val dateMillis = visitObj.getLong("dateMillis")
                    val time = visitObj.getString("time")
                    val activitiesArray = visitObj.optJSONArray("activities")
                    val acts = mutableListOf<Activity>()
                    if (activitiesArray != null) {
                        for (j in 0 until activitiesArray.length()) {
                            val actObj = activitiesArray.getJSONObject(j)
                            acts.add(Activity(actObj.getString("name"), actObj.getDouble("price")))
                        }
                    }
                    allDestinations.find { it.id == destId }?.let { scheduledVisits.add(ScheduledVisit(it, Date(dateMillis), time, acts)) }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveState() {
        val prefs = context.getSharedPreferences("NasugBrowseData", Context.MODE_PRIVATE)
        val json = JSONObject()
        val favArray = JSONArray()
        favoriteDestinations.forEach { favArray.put(it.id) }
        json.put("favorites", favArray)
        val visitsArray = JSONArray()
        scheduledVisits.forEach { visit ->
            val visitObj = JSONObject()
            visitObj.put("destId", visit.destination.id)
            visitObj.put("dateMillis", visit.date.time)
            visitObj.put("time", visit.time)
            val actsArray = JSONArray()
            visit.selectedActivities.forEach { act ->
                val actObj = JSONObject()
                actObj.put("name", act.name)
                actObj.put("price", act.price)
                actsArray.put(actObj)
            }
            visitObj.put("activities", actsArray)
            visitsArray.put(visitObj)
        }
        json.put("visits", visitsArray)
        prefs.edit().putString("planner_data_$ownerEmail", json.toString()).apply()
    }

    fun addVisit(dest: Destination, date: Date, time: String, activities: List<Activity>) {
        if (scheduledVisits.none { it.destination.id == dest.id }) {
            scheduledVisits.add(ScheduledVisit(dest, date, time, activities))
            saveState()
        }
    }
    fun removeVisit(dest: Destination) { scheduledVisits.removeAll { it.destination.id == dest.id }; saveState() }
    fun toggleFavorite(dest: Destination) {
        if (favoriteDestinations.any { it.id == dest.id }) favoriteDestinations.removeAll { it.id == dest.id }
        else favoriteDestinations.add(dest)
        saveState()
    }
}

// =====================================================
// 2. NOTIFICATIONS HELPER
// =====================================================
class NotificationHelper(private val context: Context) {
    fun scheduleTripNotifications(visit: ScheduledVisit) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val tripCal = Calendar.getInstance().apply { time = visit.date }

        val cal24h = tripCal.clone() as Calendar
        cal24h.add(Calendar.DAY_OF_YEAR, -1)
        setAlarm(alarmManager, cal24h.timeInMillis, visit, 1001, "Trip Tomorrow!", "Your trip to ${visit.destination.name} is in 24 hours.")

        val cal12h = tripCal.clone() as Calendar
        cal12h.add(Calendar.HOUR_OF_DAY, -12)
        setAlarm(alarmManager, cal12h.timeInMillis, visit, 1002, "Trip in 12 Hours!", "Get ready for your trip to ${visit.destination.name}!")
    }

    private fun setAlarm(alarmManager: AlarmManager, time: Long, visit: ScheduledVisit, requestCode: Int, title: String, body: String) {
        if (time < System.currentTimeMillis()) return
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("title", title)
            putExtra("body", body)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                else alarmManager.set(AlarmManager.RTC_WAKEUP, time, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
            }
        } catch (e: SecurityException) { alarmManager.set(AlarmManager.RTC_WAKEUP, time, pendingIntent) }
    }
}

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Upcoming Trip"
        val body = intent.getStringExtra("body") ?: "Check your itinerary!"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "trip_notifications"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Trip Reminders", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

// =====================================================
// 3. CLOUD & API SERVICES
// =====================================================
class AuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("NasugBrowsePrefs", Context.MODE_PRIVATE)
    fun isLoggedIn(): Boolean = prefs.getBoolean("isLoggedIn", false)
    fun getUserName(): String = prefs.getString("userName", "") ?: ""
    fun getUserEmail(): String = prefs.getString("userEmail", "") ?: ""
    fun registerAndLogin(name: String, email: String) {
        prefs.edit().apply { putBoolean("isLoggedIn", true); putString("userName", name); putString("userEmail", email); apply() }
    }
    fun logout() { prefs.edit().apply { putBoolean("isLoggedIn", false); apply() } }
}

object CloudServices {
    suspend fun fetchDestinationsFromCMS(): List<Destination> = withContext(Dispatchers.IO) {
        delay(1500)
        return@withContext listOf(
            Beach(2, "Pico de Loro Beach", "Nasugbu, Batangas", 1500.0, 4.7, R.drawable.pico_de_loro, "A high-end beach club famous for its iconic 'parrot beak' rock formation.", 14.192139, 120.602222, listOf(Activity("Island Hopping", 500.0), Activity("Snorkeling", 300.0))),
            Beach(5, "Munting Buhangin Beach", "Nasugbu, Batangas", 1500.0, 4.4, R.drawable.munting_buhangin, "A laid-back, family-friendly resort known for its fine grey sand.", 14.113222, 120.622444, listOf(Activity("Cottage Rental", 1000.0), Activity("Banana Boat", 350.0))),
            Beach(8, "Kayraang Cove", "Brgy. Calayo, Nasugbu", 1200.0, 4.8, R.drawable.kayraang_cove, "A private, protected cove with raw and rugged beauty.", 14.162639, 120.596861, listOf(Activity("Camping Fee", 200.0), Activity("Tent Rental", 500.0))),
            Beach(10, "Santelmo Cove", "Hamilo Coast, Nasugbu", 3000.0, 4.9, R.drawable.santelmo_cove, "A semi-secluded, pristine cove within the Hamilo Coast.", 14.205556, 120.594444, listOf(Activity("Boat Transfer", 1500.0), Activity("Snorkeling", 800.0))),
            Beach(11, "Tali Beach", "Brgy. Balaytigue, Nasugbu", 1800.0, 4.6, R.drawable.tali_beach, "A private beach subdivision known for its cliff jump site.", 14.123982, 120.612279, listOf(Activity("Cliff Jumping Guide", 250.0), Activity("Volleyball", 150.0))),
            Beach(16, "Natipuan White Sand", "Brgy. Natipuan, Nasugbu", 1800.0, 4.6, R.drawable.natipuan, "A sheltered, crescent-shaped coastal stretch known for fine cream-to-white sand.", 14.1174000, 120.6188000, listOf(Activity("Beach Entrance", 50.0), Activity("Table Rental", 300.0))),
            Beach(17, "Pamana Resorts", "Brgy. Calayo, Nasugbu", 250.0, 4.7, R.drawable.pamana, "Pairs a laid-back public shoreline with a pet-friendly beachfront stay.", 14.152063, 120.609312, listOf(Activity("Day Tour", 250.0), Activity("Pet Fee", 100.0))),
            Beach(12, "Canyon Cove", "Brgy. Wawa, Nasugbu", 2500.0, 4.8, R.drawable.canyon_cove, "A sheltered crescent cove framed by calm turquoise waters.", 14.0933, 120.6197, listOf(Activity("Water Park", 500.0), Activity("Paddle Boarding", 600.0))),
            Beach(18, "Matabungkay", "Brgy. Matabungkay, Nasugbu", 2500.0, 4.8, R.drawable.matabungkay, "A popular, family-friendly spot best known for its shallow waters.", 13.9475, 120.6169, listOf(Activity("Balsa (Floating Cottage)", 1500.0), Activity("Banana Boat", 400.0))),
            HistoricalSite(6, "Cape Santiago Lighthouse", "Nasugbu, Batangas", 300.0, 4.6, R.drawable.cape_santiago, "One of the oldest and tallest lighthouses in the Philippines.", 13.77139, 120.65250, 50.0, listOf(Activity("Entrance Fee", 50.0))),
            HistoricalSite(9, "Caleruega Church", "Brgy. Kaylaway, Nasugbu", 250.0, 4.7, R.drawable.caleruega, "A stunning Romanesque-style chapel perched on a hill.", 14.072583, 120.835806, 50.0, listOf(Activity("Entrance Fee", 50.0), Activity("Shuttle", 100.0))),
            HistoricalSite(14, "Nasugbu Municipal Hall", "Poblacion, Nasugbu", 0.0, 4.0, R.drawable.nasugbu_municipal, "The town center, which features a historic municipal hall.", 14.07429, 120.63249, 0.0, listOf(Activity("Walking Tour", 0.0))),
            AdventureSpot(13, "Pico de Loro (Hike)", "Nasugbu, Batangas", 700.0, 4.7, R.drawable.pico_de_loro_hike, "A popular hiking destination for its distinctive 'parrot's beak' peak.", 14.2167, 120.6500, "Hard", listOf(Activity("Guide Fee", 600.0))),
            AdventureSpot(3, "Mt. Batulao", "Nasugbu, Batangas", 800.0, 4.8, R.drawable.mt_batulao, "A popular hiking destination offering breathtaking 360-degree views.", 14.04500, 120.80444, "Moderate", listOf(Activity("Registration", 100.0), Activity("Camping", 300.0))),
            AdventureSpot(4, "Mt. Talamitam", "Nasugbu, Batangas", 600.0, 4.5, R.drawable.mt_talamitam, "A beginner-friendly mountain known for its scenic grasslands.", 14.10778, 120.75972, "Easy", listOf(Activity("Guide Fee", 400.0))),
            Restaurant(15, "Daddy Riz", "Nasugbu, Batangas", 350.0, 4.4, R.drawable.daddy_riz, "A must-visit for foodies serving the famous Batangas Lomi.", 14.0750, 120.6300, "Batangas Cuisine", listOf(Activity("Lomi Bowl", 150.0), Activity("Bulalo", 350.0))),
            Restaurant(7, "Kainan sa Dalampasigan", "Nasugbu, Batangas", 800.0, 4.5, R.drawable.kainan_sa_dalampasigan, "A famous local dining spot serving authentic Batangas dishes.", 14.072139, 120.625194, "Batangas Cuisine", listOf(Activity("Inihaw na Liempo", 250.0), Activity("Sinigang", 400.0)))
        )
    }

    suspend fun fetchWeather(lat: Double, lon: Double): WeatherInfo = withContext(Dispatchers.IO) {
        val apiKey = "YOUR_OPENWEATHER_API_KEY"
        try {
            val url = URL("https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$apiKey&units=metric")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val temp = json.getJSONObject("main").getDouble("temp")
            val condition = json.getJSONArray("weather").getJSONObject(0).getString("main")
            val icon = when(condition.lowercase()) { "clear" -> Icons.Default.WbSunny; "rain" -> Icons.Default.Umbrella; "clouds" -> Icons.Default.Cloud; else -> Icons.Default.WbCloudy }
            return@withContext WeatherInfo("${temp.toInt()}°C", condition, icon)
        } catch (e: Exception) {
            return@withContext WeatherInfo("30°C", "Sunny (Fallback)", Icons.Default.WbSunny)
        }
    }

    suspend fun syncUserDataToCloud() = withContext(Dispatchers.IO) { delay(1000) }

    val destinationReviews = mutableStateMapOf<Int, MutableList<Review>>()
}

val categories = listOf("All", "Beach", "Historical", "Adventure", "Food")

// =====================================================
// 4. MAIN ACTIVITY
// =====================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val authManager = remember { AuthManager(context) }
            var isDarkMode by remember { mutableStateOf(false) }

            val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            MaterialTheme(colorScheme = if (isDarkMode) DarkColors else LightColors) {
                var showSplash by remember { mutableStateOf(true) }
                var isLoggedIn by remember { mutableStateOf(authManager.isLoggedIn()) }

                LaunchedEffect(Unit) { delay(2000); showSplash = false }

                if (showSplash) {
                    SplashScreen()
                } else {
                    if (isLoggedIn) {
                        val planner = remember { PlannerSystem(authManager.getUserName(), authManager.getUserEmail(), context) }
                        MainApp(planner, isDarkMode, onToggleTheme = { isDarkMode = !isDarkMode }, onLogout = { authManager.logout(); isLoggedIn = false })
                    } else {
                        AuthScreen(authManager) { isLoggedIn = true }
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PrimaryBlue, Color(0xFF0F172A)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.FlightTakeoff, contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text("NasugBrowse", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = AccentOrange)
        }
    }
}

@Composable
fun AuthScreen(authManager: AuthManager, onLoginSuccess: () -> Unit) {
    var isLoginMode by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.TravelExplore, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(if (isLoginMode) "Welcome Back" else "Create Account", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Browse the best of Nasugbu, Batangas", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(32.dp))

        if (errorMsg.isNotEmpty()) Text(errorMsg, color = Color.Red, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))

        if (!isLoginMode) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)); Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)); Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) } },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (email.isBlank() || password.isBlank() || (!isLoginMode && name.isBlank())) errorMsg = "Please fill in all fields."
                else {
                    isLoading = true
                    scope.launch {
                        CloudServices.syncUserDataToCloud()
                        authManager.registerAndLogin(if (isLoginMode) email.substringBefore("@") else name, email)
                        isLoading = false
                        onLoginSuccess()
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            else Text(if (isLoginMode) "Log In" else "Sign Up", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { isLoginMode = !isLoginMode; errorMsg = "" }) { Text(if (isLoginMode) "Don't have an account? Sign Up" else "Already have an account? Log In") }
    }
}

// =====================================================
// 5. MAIN APP NAVIGATION
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(planner: PlannerSystem, isDarkMode: Boolean, onToggleTheme: () -> Unit, onLogout: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedDestinationDetail by remember { mutableStateOf<Destination?>(null) }
    var showTerms by remember { mutableStateOf(false) }

    var allDestinations by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var isDestinationsLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        allDestinations = CloudServices.fetchDestinationsFromCMS()
        planner.loadSavedState(allDestinations)
        isDestinationsLoading = false
    }

    val bgResId = if (isDarkMode) R.drawable.bg_night else R.drawable.bg_morning
    // Lighter overlay so the glass effect is more visible over the background
    val overlayColor = if (isDarkMode) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f)

    if (showTerms) TermsAndPolicyDialog(onDismiss = { showTerms = false })

    Box(modifier = Modifier.fillMaxSize()) {
        Image(painter = painterResource(id = bgResId), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(overlayColor))

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // Glassy Navigation Bar
                NavigationBar(
                    containerColor = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f),
                    tonalElevation = 0.dp,
                    modifier = Modifier.border(BorderStroke(1.dp, if (isDarkMode) GlassBorderDark else GlassBorderLight), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                ) {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Explore, contentDescription = null) }, label = { Text("Explore", fontSize = 10.sp) })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Favorite, contentDescription = null) }, label = { Text("Saved", fontSize = 10.sp) })
                    NavigationBarItem(
                        selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        icon = { BadgedBox(badge = { if (planner.scheduledVisits.isNotEmpty()) Badge { Text(planner.scheduledVisits.size.toString()) } }) { Icon(Icons.Default.Map, contentDescription = null) } },
                        label = { Text("Itinerary", fontSize = 10.sp) }
                    )
                    NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.PriceCheck, contentDescription = null) }, label = { Text("Prices", fontSize = 10.sp) })
                    NavigationBarItem(selected = selectedTab == 4, onClick = { selectedTab = 4 }, icon = { Icon(Icons.Default.Person, contentDescription = null) }, label = { Text("Profile", fontSize = 10.sp) })
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (isDestinationsLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    when (selectedTab) {
                        0 -> ExploreScreen(allDestinations, planner, onDetails = { selectedDestinationDetail = it }, snackbar = { scope.launch { snackbarHostState.showSnackbar(it) } })
                        1 -> FavoritesScreen(planner, onDetails = { selectedDestinationDetail = it })
                        2 -> ItineraryScreen(planner, onDetails = { selectedDestinationDetail = it })
                        3 -> PricesScreen(allDestinations)
                        4 -> ProfileScreen(planner, isDarkMode, onToggleTheme, onLogout, onShowTerms = { showTerms = true })
                    }
                }
            }
        }

        if (selectedDestinationDetail != null) {
            DestinationDetailSheet(selectedDestinationDetail!!, planner, onDismiss = { selectedDestinationDetail = null })
        }
    }
}

// =====================================================
// 6. SCREENS
// =====================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(destinations: List<Destination>, planner: PlannerSystem, onDetails: (Destination) -> Unit, snackbar: (String) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var destinationToSchedule by remember { mutableStateOf<Destination?>(null) }
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background

    if (destinationToSchedule != null) {
        ScheduleDestinationDialog(
            destination = destinationToSchedule!!,
            onDismiss = { destinationToSchedule = null },
            onScheduled = { date, time, activities ->
                planner.addVisit(destinationToSchedule!!, date, time, activities)
                val visit = planner.scheduledVisits.find { it.destination.id == destinationToSchedule!!.id }
                if (visit != null) NotificationHelper(context).scheduleTripNotifications(visit)
                destinationToSchedule = null
            }
        )
    }

    val filtered = destinations.filter { dest ->
        val matchesCategory = selectedCategory == "All" || dest.category() == selectedCategory
        val matchesSearch = dest.name.contains(searchQuery, ignoreCase = true) || dest.location.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Hi, ${planner.ownerName.split(" ").first()} 👋", fontSize = 16.sp, color = if(isDark) Color.White else Color(0xFF1E293B), modifier = Modifier.padding(top = 16.dp))
        Text("Browse Destinations", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = if(isDark) Color.White else Color(0xFF1E293B), modifier = Modifier.padding(bottom = 16.dp))

        // Glassy Search Bar
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search destinations...") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = if (isDark) GlassDark else GlassWhite,
                unfocusedContainerColor = if (isDark) GlassDark else GlassWhite,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { category ->
                FilterChip(
                    selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White, labelColor = if(isDark) Color.White else Color(0xFF1E293B)),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = false, borderColor = if(isDark) GlassBorderDark else GlassBorderLight)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(filtered, key = { it.id }) { dest ->
                val isAdded = planner.scheduledVisits.any { it.destination.id == dest.id }
                val isFav = planner.favoriteDestinations.any { it.id == dest.id }
                DestinationCard(dest, isAdded, isFav, onClick = { onDetails(dest) }, onFavToggle = { planner.toggleFavorite(dest) }) {
                    if (!isAdded) destinationToSchedule = dest
                    else { planner.removeVisit(dest); snackbar("Removed from Itinerary") }
                }
            }
        }
    }
}

@Composable
fun DestinationCard(dest: Destination, isAdded: Boolean, isFav: Boolean, onClick: () -> Unit, onFavToggle: () -> Unit, onAddToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp).clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(painter = painterResource(id = dest.imageResId), contentDescription = dest.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)), startY = 300f)))

            IconButton(onClick = onFavToggle, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.White.copy(alpha = 0.3f), CircleShape)) {
                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null, tint = if (isFav) Color.Red else Color.White)
            }

            Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(dest.name, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                    Text(dest.location, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("₱${"%.0f".format(dest.baseCost)}", fontWeight = FontWeight.ExtraBold, color = AccentOrange, fontSize = 20.sp)
                    FilledIconButton(onClick = onAddToggle, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(if (isAdded) Icons.Default.Check else Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        }
    }
}

// =====================================================
// 7. ITINERARY
// =====================================================
@Composable
fun ItineraryScreen(planner: PlannerSystem, onDetails: (Destination) -> Unit) {
    val dateFormatter = DateFormat.getMediumDateFormat(LocalContext.current)
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background

    Column(modifier = Modifier.fillMaxSize()) {
        Text("My Itinerary", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if(isDark) Color.White else Color(0xFF1E293B), modifier = Modifier.padding(16.dp))
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Scheduled Places to Visit", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if(isDark) Color.White else Color(0xFF1E293B)) }
            if (planner.scheduledVisits.isEmpty()) {
                item { Text("Your itinerary is empty. Go to Explore to add places.", color = if(isDark) Color.White.copy(alpha=0.8f) else Color(0xFF1E293B)) }
            } else {
                items(planner.scheduledVisits, key = { it.destination.id }) { visit ->
                    GlassCard(modifier = Modifier.fillMaxWidth().clickable { onDetails(visit.destination) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(visit.destination.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(visit.destination.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("${dateFormatter.format(visit.date)} at ${visit.time}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                            }
                            IconButton(onClick = { planner.removeVisit(visit.destination) }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red) }
                        }

                        if (visit.selectedActivities.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Planned Activities:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                            visit.selectedActivities.forEach { act ->
                                Text("• ${act.name} (₱${act.price.toInt()})", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================
// 8. SCHEDULE DIALOG WITH ACTIVITIES
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDestinationDialog(destination: Destination, onDismiss: () -> Unit, onScheduled: (Date, String, List<Activity>) -> Unit) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<Date?>(null) }
    var selectedTime by remember { mutableStateOf<String?>(null) }
    val selectedActivities = remember { mutableStateListOf<Activity>() }
    val datePickerState = rememberDatePickerState()

    val isDark = MaterialTheme.colorScheme.background == DarkColors.background

    if (showDatePicker) {
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextButton(onClick = { datePickerState.selectedDateMillis?.let { selectedDate = Date(it) }; showDatePicker = false }) { Text("Confirm") }
        }) { DatePicker(state = datePickerState) }
    }

    if (showTimePicker) {
        CustomTimePickerDialog(onDismiss = { showTimePicker = false }, onTimeSelected = { time -> selectedTime = time; showTimePicker = false })
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            colors = CardDefaults.cardColors(containerColor = if(isDark) Color(0xFF1E293B).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f)),
            border = BorderStroke(1.dp, if(isDark) GlassBorderDark else GlassBorderLight)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Schedule Visit", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(destination.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, if(isDark) GlassBorderDark else GlassBorderLight)
                ) { Text(if (selectedDate != null) DateFormat.getMediumDateFormat(LocalContext.current).format(selectedDate!!) else "Pick Date") }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, if(isDark) GlassBorderDark else GlassBorderLight)
                ) { Text(selectedTime ?: "Pick Time") }

                if (destination.activities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select Activities:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(destination.activities) { activity ->
                            val isChecked = selectedActivities.any { it.name == activity.name }
                            Row(modifier = Modifier.fillMaxWidth().clickable {
                                if (isChecked) selectedActivities.remove(activity) else selectedActivities.add(activity)
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { if (isChecked) selectedActivities.remove(activity) else selectedActivities.add(activity) },
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(activity.name, fontSize = 14.sp)
                                    Text("₱${activity.price.toInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { if (selectedDate != null && selectedTime != null) onScheduled(selectedDate!!, selectedTime!!, selectedActivities.toList()) },
                        enabled = selectedDate != null && selectedTime != null,
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Add to Trip") }
                }
            }
        }
    }
}

// =====================================================
// 9. DETAILS, MAP, WEATHER & REVIEWS
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationDetailSheet(dest: Destination, planner: PlannerSystem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showAddReview by remember { mutableStateOf(false) }
    val reviews = CloudServices.destinationReviews[dest.id] ?: mutableListOf()
    var weatherInfo by remember { mutableStateOf<WeatherInfo?>(null) }
    LaunchedEffect(dest.id) { weatherInfo = CloudServices.fetchWeather(dest.latitude, dest.longitude) }

    val isDark = MaterialTheme.colorScheme.background == DarkColors.background

    if (showAddReview) {
        AddReviewDialog(
            onDismiss = { showAddReview = false },
            onSubmit = { rating, comment ->
                if (CloudServices.destinationReviews[dest.id] == null) CloudServices.destinationReviews[dest.id] = mutableListOf()
                CloudServices.destinationReviews[dest.id]?.add(Review(planner.ownerName, rating, comment))
                showAddReview = false
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if(isDark) Color(0xFF0F172A).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f)) {
        LazyColumn(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            item {
                Image(painter = painterResource(id = dest.imageResId), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)))
                Spacer(modifier = Modifier.height(16.dp))
                Text(dest.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                    Text(dest.location, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f), fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(dest.description, fontSize = 15.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface)

                Spacer(modifier = Modifier.height(16.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (weatherInfo != null) {
                            Icon(weatherInfo!!.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = AccentOrange)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Live Weather", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                                Text("${weatherInfo!!.temp} • ${weatherInfo!!.condition}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Fetching live weather...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Live Location Map", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))

                val markerState = remember { MarkerState(position = LatLng(dest.latitude, dest.longitude)) }
                val cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(markerState.position, 12f) }

                Box(modifier = Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, if(isDark) GlassBorderDark else GlassBorderLight, RoundedCornerShape(16.dp))) {
                    GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = cameraPositionState) {
                        Marker(state = markerState, title = dest.name, snippet = dest.location)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val gmmIntentUri = android.net.Uri.parse("google.navigation:q=${dest.latitude},${dest.longitude}")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply { setPackage("com.google.android.apps.maps") }
                        if (mapIntent.resolveActivity(context.packageManager) != null) context.startActivity(mapIntent)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${dest.latitude},${dest.longitude}")))
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)
                ) { Icon(Icons.Default.Directions, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Start Turn-by-Turn Navigation") }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Reviews (${reviews.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { showAddReview = true }) { Text("Write a Review") }
                }
            }

            if (reviews.isEmpty()) {
                item { Text("No reviews yet. Be the first to review!", color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f), fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                items(reviews) { review ->
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(review.author, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            repeat(5) { index ->
                                Icon(if (index < review.rating) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(14.dp))
                            }
                        }
                        Text(review.comment, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.8f))
                    }
                }
            }
        }
    }
}

// =====================================================
// 10. HELPERS & OTHER SCREENS
// =====================================================
@Composable
fun CustomTimePickerDialog(onDismiss: () -> Unit, onTimeSelected: (String) -> Unit) {
    val hours = (1..12).toList()
    val minutes = (0..59).map { String.format("%02d", it) }
    var selectedPeriod by remember { mutableStateOf("AM") }

    val hourState = rememberLazyListState(initialFirstVisibleItemIndex = 9)
    val minuteState = rememberLazyListState(initialFirstVisibleItemIndex = 0)

    val isDark = MaterialTheme.colorScheme.background == DarkColors.background

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if(isDark) Color(0xFF1E293B).copy(alpha=0.95f) else Color.White.copy(alpha=0.95f)),
            border = BorderStroke(1.dp, if(isDark) GlassBorderDark else GlassBorderLight)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Set Start Time", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.height(120.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    WheelPicker(items = hours, state = hourState, modifier = Modifier.weight(1f))
                    Text(":", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                    WheelPicker(items = minutes, state = minuteState, modifier = Modifier.weight(1f))

                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("AM",
                            fontSize = if (selectedPeriod == "AM") 24.sp else 18.sp,
                            fontWeight = if (selectedPeriod == "AM") FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedPeriod == "AM") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f),
                            modifier = Modifier.clickable { selectedPeriod = "AM" }.padding(8.dp)
                        )
                        Text("PM",
                            fontSize = if (selectedPeriod == "PM") 24.sp else 18.sp,
                            fontWeight = if (selectedPeriod == "PM") FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedPeriod == "PM") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f),
                            modifier = Modifier.clickable { selectedPeriod = "PM" }.padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        val h = hours.getOrElse(hourState.firstVisibleItemIndex) { 10 }
                        val m = minutes.getOrElse(minuteState.firstVisibleItemIndex) { 0 }
                        onTimeSelected(String.format("%02d:%s %s", h, m, selectedPeriod))
                    }, modifier = Modifier.padding(start = 8.dp)) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun <T> WheelPicker(items: List<T>, state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(120.dp), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 48.dp)
        ) {
            items(items.size) { index ->
                val isSelected = state.firstVisibleItemIndex == index
                Text(
                    text = items[index].toString(), fontSize = if (isSelected) 24.sp else 18.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(36.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.3f), RoundedCornerShape(8.dp)))
    }
}

@Composable
fun PricesScreen(destinations: List<Destination>) {
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Price Guide & Activities", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if(isDark) Color.White else Color(0xFF1E293B), modifier = Modifier.padding(bottom = 8.dp)) }
        items(destinations) { dest ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(dest.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(dest.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Base Cost / Entrance:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                Text("₱${"%.2f".format(dest.baseCost)}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))
                Text("Available Activities:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                dest.activities.forEach { activity ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(activity.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("₱${"%.2f".format(activity.price)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(planner: PlannerSystem, onDetails: (Destination) -> Unit) {
    if (planner.favoriteDestinations.isEmpty()) {
        EmptyState("No saved places", "Tap the heart icon to save it here.")
    } else {
        LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(planner.favoriteDestinations, key = { it.id }) { dest ->
                Card(
                    modifier = Modifier.clickable { onDetails(dest) },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha=0.3f))
                ) {
                    Column {
                        Image(painter = painterResource(id = dest.imageResId), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.height(120.dp).fillMaxWidth())
                        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha=0.8f)).padding(8.dp).fillMaxWidth()) {
                            Text(dest.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(planner: PlannerSystem, isDarkMode: Boolean, onToggleTheme: () -> Unit, onLogout: () -> Unit, onShowTerms: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
        Text(planner.ownerName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if(isDarkMode) Color.White else Color(0xFF1E293B))
        Text(planner.ownerEmail, color = if(isDarkMode) Color.White.copy(alpha = 0.8f) else Color(0xFF1E293B).copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(32.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Saved Destinations", color = MaterialTheme.colorScheme.onSurface); Text("${planner.favoriteDestinations.size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Itinerary Items", color = MaterialTheme.colorScheme.onSurface); Text("${planner.scheduledVisits.size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))
            Row(modifier = Modifier.fillMaxWidth().clickable { onShowTerms() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Terms & Privacy Policy", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Dark Mode", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = isDarkMode, onCheckedChange = { onToggleTheme() })
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onLogout, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red), border = BorderStroke(1.dp, Color.Red)
        ) { Icon(Icons.Default.Logout, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Log Out", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(80.dp), tint = if(isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF1E293B).copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if(isDark) Color.White else Color(0xFF1E293B))
        Text(subtitle, textAlign = TextAlign.Center, color = if(isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF1E293B).copy(alpha = 0.6f))
    }
}

@Composable
fun TermsAndPolicyDialog(onDismiss: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(400.dp),
            colors = CardDefaults.cardColors(containerColor = if(isDark) Color(0xFF1E293B).copy(alpha=0.95f) else Color.White.copy(alpha=0.95f)),
            border = BorderStroke(1.dp, if(isDark) GlassBorderDark else GlassBorderLight)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Terms & Privacy Policy", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn {
                        item {
                            Text("1. Acceptance of Terms", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("By using NasugBrowse, you agree to these terms. This app is an informational itinerary planner.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f), modifier = Modifier.padding(bottom = 12.dp))
                            Text("2. User Accounts", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("You are responsible for maintaining the confidentiality of your account details.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f), modifier = Modifier.padding(bottom = 12.dp))
                            Text("3. Pricing & Accuracy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Prices listed in the Price Guide are estimates. Actual costs may vary.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("I Understand") }
            }
        }
    }
}

@Composable
fun AddReviewDialog(onDismiss: () -> Unit, onSubmit: (Int, String) -> Unit) {
    var rating by remember { mutableIntStateOf(5) }
    var comment by remember { mutableStateOf("") }
    val isDark = MaterialTheme.colorScheme.background == DarkColors.background

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if(isDark) Color(0xFF1E293B).copy(alpha=0.95f) else Color.White.copy(alpha=0.95f)),
            border = BorderStroke(1.dp, if(isDark) GlassBorderDark else GlassBorderLight)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Write a Review", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    repeat(5) { index ->
                        IconButton(onClick = { rating = index + 1 }) {
                            Icon(if (index < rating) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(32.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("Share your experience...") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { if (comment.isNotBlank()) onSubmit(rating, comment) }, modifier = Modifier.padding(start = 8.dp)) { Text("Submit") }
                }
            }
        }
    }
}