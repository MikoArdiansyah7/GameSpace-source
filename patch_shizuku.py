# Ubah lambda / method-reference Shizuku API jadi anonymous class (dx tidak mendukung invokedynamic).
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])

def rw(path, fn):
    p = next(root.rglob(path))
    s = p.read_text(); n = fn(s); assert n != s, path; p.write_text(n)

def shizuku(s):
    s = s.replace("""DEATH_RECIPIENT = () -> {
        binderReady = false;
        onBinderReceived(null, null);
    };""", """DEATH_RECIPIENT = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            binderReady = false;
            onBinderReceived(null, null);
        }
    };""")
    s = re.sub(r"([\w.]+)\.post\(((?:holder\.)?listener)::(onBinder\w+)\);",
               r"\1.post(new Runnable() { @Override public void run() { \2.\3(); } });", s)
    s = re.sub(r"([\w.]+)\.post\(\(\) -> holder\.listener\.onRequestPermissionResult\(requestCode, result\)\);",
               r"\1.post(new Runnable() { @Override public void run() { holder.listener.onRequestPermissionResult(requestCode, result); } });", s)
    types = {"RECEIVED_LISTENERS": "OnBinderReceivedListener", "DEAD_LISTENERS": "OnBinderDeadListener",
             "PERMISSION_LISTENERS": "OnRequestPermissionResultListener"}
    def rm(m):
        t = types[m.group(1)]
        return (m.group(1) + ".removeIf(new java.util.function.Predicate<ListenerHolder<" + t + ">>() { "
                "@Override public boolean test(ListenerHolder<" + t + "> holder) { return holder.listener == listener; } })")
    s = re.sub(r"(\w+)\.removeIf\(holder -> holder\.listener == listener\)", rm, s)
    return s

def remote(s):
    return s.replace("""linkToDeath((IBinder.DeathRecipient) () -> {
                this.remote = null;
                Log.v(TAG, "remote process is dead");

                CACHE.remove(ShizukuRemoteProcess.this);
            }, 0);""", """linkToDeath(new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    ShizukuRemoteProcess.this.remote = null;
                    Log.v(TAG, "remote process is dead");
                    CACHE.remove(ShizukuRemoteProcess.this);
                }
            }, 0);""")

def conn(s):
    s = re.sub(r"MAIN_HANDLER\.post\(\(\) -> \{\s*for \(ServiceConnection conn : connections\) \{\s*conn\.onServiceConnected\(componentName, binder\);\s*\}\s*\}\s*\);",
        "MAIN_HANDLER.post(new Runnable() { @Override public void run() { for (ServiceConnection conn : connections) { conn.onServiceConnected(componentName, binder); } } });", s)
    s = s.replace("linkToDeath(this::died, 0);",
        "linkToDeath(new IBinder.DeathRecipient() { @Override public void binderDied() { ShizukuServiceConnection.this.died(); } }, 0);")
    s = re.sub(r"MAIN_HANDLER\.post\(\(\) -> \{\s*for \(ServiceConnection conn : connections\) \{\s*conn\.onServiceDisconnected\(componentName\);\s*\}\s*connections\.clear\(\);\s*ShizukuServiceConnections\.remove\(this\);\s*\}\s*\);",
        "MAIN_HANDLER.post(new Runnable() { @Override public void run() { for (ServiceConnection conn : connections) { conn.onServiceDisconnected(componentName); } connections.clear(); ShizukuServiceConnections.remove(ShizukuServiceConnection.this); } });", s)
    return s

rw("Shizuku.java", shizuku)
rw("ShizukuRemoteProcess.java", remote)
rw("ShizukuServiceConnection.java", conn)
left = [str(p) for p in root.rglob("*.java") if re.search(r"->|\w::\w", re.sub(r"(//.*|/\*.*?\*/)", "", p.read_text(), flags=re.S))]
print("lambda tersisa:", left)
