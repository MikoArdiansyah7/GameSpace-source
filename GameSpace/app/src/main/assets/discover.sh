echo "uid=$(id -u)"
echo "model=$(getprop ro.product.model)"
echo "brand=$(getprop ro.product.brand)"
echo "plat=$(getprop ro.board.platform)"
echo "soc=$(getprop ro.soc.model)"
echo "sdk=$(getprop ro.build.version.sdk)"
echo "rel=$(getprop ro.build.version.release)"
command -v su >/dev/null 2>&1 && echo "su=1"
n=0
for p in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$p" ] || continue
  n=1; w=0; [ -w "$p/scaling_min_freq" ] && w=1
  g=0; [ -w "$p/scaling_governor" ] && g=1
  echo "cpu=$p|$(cat $p/cpuinfo_max_freq 2>/dev/null)|$(cat $p/cpuinfo_min_freq 2>/dev/null)|$(cat $p/scaling_min_freq 2>/dev/null)|$(cat $p/scaling_max_freq 2>/dev/null)|$(cat $p/scaling_governor 2>/dev/null)|$w|$g|$(cat $p/scaling_available_frequencies 2>/dev/null | tr ' \n' ',')"
done
if [ $n = 0 ]; then
  for p in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do
    [ -d "$p" ] || continue
    w=0; [ -w "$p/scaling_min_freq" ] && w=1
    echo "cpu=$p|$(cat $p/cpuinfo_max_freq 2>/dev/null)|$(cat $p/cpuinfo_min_freq 2>/dev/null)|$(cat $p/scaling_min_freq 2>/dev/null)|$(cat $p/scaling_max_freq 2>/dev/null)|$(cat $p/scaling_governor 2>/dev/null)|$w|0|$(cat $p/scaling_available_frequencies 2>/dev/null | tr ' \n' ',')"
  done
fi
for f in /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/devfreq/*mali*/cur_freq /sys/class/devfreq/*gpu*/cur_freq /sys/kernel/gpu/gpu_clock /sys/kernel/ged/hal/current_freqency; do
  if [ -r "$f" ]; then echo "gfreq=$f"; break; fi
done
for f in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpubusy /sys/kernel/ged/hal/gpu_utilization /sys/kernel/gpu/gpu_busy /sys/class/devfreq/*mali*/load; do
  if [ -r "$f" ]; then echo "gload=$f"; break; fi
done
kd=/sys/class/kgsl/kgsl-3d0
if [ -d $kd ]; then
  echo "gkind=kgsl"; echo "gdev=$kd"
  fl=$(cat $kd/devfreq/available_frequencies 2>/dev/null)
  [ -z "$fl" ] && fl=$(cat $kd/gpu_available_frequencies 2>/dev/null)
  echo "gfreqs=$(echo $fl | tr ' ' ',')"
  w=0; [ -w $kd/devfreq/min_freq ] && w=1; echo "gw=$w"
elif [ -d /proc/gpufreqv2 ]; then
  echo "gkind=mtk2"
  echo "gopp=$(cat /proc/gpufreqv2/gpu_working_opp_table 2>/dev/null | tr '\n' ';')"
  w=0; [ -w /proc/gpufreqv2/fix_target_opp_index ] && w=1; echo "gw=$w"
elif [ -d /proc/gpufreq ]; then
  echo "gkind=mtk1"
  echo "gopp=$(cat /proc/gpufreq/gpufreq_opp_dump 2>/dev/null | tr '\n' ';')"
  w=0; [ -w /proc/gpufreq/gpufreq_opp_freq ] && w=1; echo "gw=$w"
else
  d=$(ls -d /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu* 2>/dev/null | head -n1)
  if [ -n "$d" ]; then
    echo "gkind=devfreq"; echo "gdev=$d"
    echo "gfreqs=$(cat $d/available_frequencies 2>/dev/null | tr ' ' ',')"
    w=0; [ -w $d/min_freq ] && w=1; echo "gw=$w"
  fi
fi
ged=0; [ -w /sys/module/ged/parameters/gpu_cust_boost_freq ] && ged=1; echo "ged=$ged"
for z in /sys/class/thermal/thermal_zone*; do
  [ -r "$z/type" ] && echo "tz=$z|$(cat $z/type 2>/dev/null)"
done
echo "end=1"
