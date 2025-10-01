# MAD Word Guessing Game (Android – Kotlin + Jetpack Compose)

A mobile word-guessing game

## ✨ Features (mapped to marking guide)

- **Onboarding (SharedPreferences)**: asks and persists player name.  
- **Guess the word**: 10 attempts, −10 points per wrong guess, starts at 100.  
- **Hint – length**: “How many letters?” for −5 points.  
- **Hint – occurrences**: check how many times a letter appears for −5 points.  
- **Tip**: after 5 attempts, reveals first letter (one-time).  
- **Timer**: counts seconds to correct guess.  
- **Leveling**: on correct guess, advance to higher level with longer words.  
- **Leaderboard **: dreamlo.com integration (HTTP).  
- **Dashboard**: previous rounds history + aggregates (Best Score, Fastest Time, Games, Highest Level).  
- **UI/UX**: Compose Material 3, bottom navigation, gradient top bar, snackbars for hints.

## 🏗️ Architecture

- **UI**: Jetpack Compose Material 3 + Navigation-Compose  
- **State**: `GameVm` `ViewModel` (Kotlin coroutines)  
- **Network**: Retrofit + Moshi for Random Word API  
- **Storage**: `SharedPreferences` (player name, round history JSON)  
- **Leaderboard**: dreamlo HTTP via OkHttp (optional)

## 🔌 APIs

- **Random word**: `https://random-word-api.herokuapp.com/word`  
- **dreamlo** : `http://dreamlo.com/lb/<PUBLIC|PRIVATE>/...`


## ⚙️ Setup

1. **Android Studio**: Koala or later  
2. **Java**: JDK 17  
3. **Kotlin**: 2.0.x, Compose Compiler plugin  
4. **Gradle deps** (already in project):
   - `androidx.navigation:navigation-compose:2.7.7`
   - Compose Material3, Retrofit, Moshi, OkHttp, Coroutines
