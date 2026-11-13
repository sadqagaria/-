Smoke-test is a pure Kotlin parser test and does not require Android SDK:
  kotlinc app/src/main/java/com/satelliteworld/tv/data/PlaylistParser.kt tools/ParserSmokeTest.kt -include-runtime -d /tmp/swt-parser.jar
  java -jar /tmp/swt-parser.jar
