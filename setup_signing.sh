#!/data/data/com.termux/files/usr/bin/bash
# ينشئ مفتاح توقيع ثابت للتطبيق ويرفعه كأسرار (Secrets) في GitHub.
# الاستخدام:  bash setup_signing.sh اسم-حسابك/QuickPlayer
set -e
REPO="$1"
if [ -z "$REPO" ]; then
  echo "اكتب اسم الريبو هكذا:  bash setup_signing.sh Khereba1/QuickPlayer"
  exit 1
fi
command -v keytool >/dev/null 2>&1 || { echo "تثبيت Java (لمرة واحدة)..."; pkg install -y openjdk-17; }
command -v gh >/dev/null 2>&1 || pkg install -y gh

DIR="$HOME/quickplayer-key"
KS="$DIR/quickplayer.jks"
mkdir -p "$DIR"
if [ -f "$KS" ]; then
  echo "المفتاح موجود بالفعل في $KS"
  echo "لو عايز تستخدمه نفسه اكتب الباسورد من ملف password.txt اللي جنبه، ولو مفقود ابدأ بنسخة احتياطية."
  exit 1
fi

PASS=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
ALIAS="quickplayer"

keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
  -validity 36500 -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Khereba1, O=Khereba1, C=EG"

printf '%s' "$PASS" > "$DIR/password.txt"
printf '%s' "$ALIAS" > "$DIR/alias.txt"

base64 -w0 "$KS" | gh secret set KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$PASS" | gh secret set KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$ALIAS" | gh secret set KEY_ALIAS -R "$REPO"
printf '%s' "$PASS" | gh secret set KEY_PASSWORD -R "$REPO"

# نسخة احتياطية في Download (مهمة جداً: لو ضاع المفتاح مش هتقدر تحدّث التطبيق فوق القديم)
if [ -d "$HOME/storage/downloads" ]; then
  mkdir -p "$HOME/storage/downloads/QuickPlayer-KEY-BACKUP"
  cp "$KS" "$DIR/password.txt" "$DIR/alias.txt" "$HOME/storage/downloads/QuickPlayer-KEY-BACKUP/"
  echo "تم حفظ نسخة احتياطية في: Download/QuickPlayer-KEY-BACKUP"
fi
echo
echo "تمام ✅ المفتاح اتحفظ في GitHub Secrets. انقل مجلد QuickPlayer-KEY-BACKUP لمكان آمن (Drive أو غيره)."
