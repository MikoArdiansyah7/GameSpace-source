GAME="@GAME@"
IME=$(settings get secure default_input_method 2>/dev/null | cut -d/ -f1)
for p in $(pm list packages -3 2>/dev/null | cut -d: -f2); do
  case "$p" in
    "$GAME"|com.alya.gamespace|moe.shizuku.privileged.api|"$IME") ;;
    *) am kill "$p" >/dev/null 2>&1 ;;
  esac
done
am kill-all >/dev/null 2>&1
echo done
