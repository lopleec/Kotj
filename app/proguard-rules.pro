# Kotj uses explicit JSON encoding and Android framework entry points, so it does not require
# broad keep rules. Keep source/line metadata so release crash reports remain actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
