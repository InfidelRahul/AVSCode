#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <thread>
#include <atomic>
#include <fstream>
#include <sstream>

#define LOG_TAG "AVscodeNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<int> g_current_pid(-1);
static std::string g_rootfs_path;
static std::string g_native_lib_dir;
static bool g_initialized = false;

static std::string get_proot_path() {
    std::vector<std::string> candidates = {
        g_native_lib_dir + "/libproot.so",
        g_native_lib_dir + "/libproot-bin.so",
        "/data/data/com.avscode/lib/libproot.so"
    };
    for (const auto& path : candidates) {
        if (access(path.c_str(), F_OK) == 0) {
            return path;
        }
    }
    return g_native_lib_dir + "/libproot.so";
}

static char** vector_to_argv(std::vector<std::string>& args) {
    char** argv = new char*[args.size() + 1];
    for (size_t i = 0; i < args.size(); ++i) {
        argv[i] = new char[args[i].size() + 1];
        strcpy(argv[i], args[i].c_str());
    }
    argv[args.size()] = nullptr;
    return argv;
}

static void free_argv(char** argv, size_t size) {
    for (size_t i = 0; i < size; ++i) {
        delete[] argv[i];
    }
    delete[] argv;
}

static int execute_command(const char* command, jobject callback, JNIEnv* env) {
    FILE* pipe = popen(command, "r");
    if (!pipe) {
        LOGE("Failed to execute command: %s", command);
        return -1;
    }

    jclass callback_class = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID on_output_method = callback_class ? 
        env->GetMethodID(callback_class, "onOutput", "(Ljava/lang/String;)V") : nullptr;

    char buffer[4096];
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        std::string line(buffer);
        if (!line.empty() && line.back() == '\n') {
            line.pop_back();
        }
        
        if (callback != nullptr && on_output_method != nullptr) {
            jstring java_line = env->NewStringUTF(line.c_str());
            env->CallVoidMethod(callback, on_output_method, java_line);
            env->DeleteLocalRef(java_line);
        }
    }

    int exit_code = pclose(pipe);
    if (callback_class) env->DeleteLocalRef(callback_class);
    return WEXITSTATUS(exit_code);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeInit(
    JNIEnv* env, jobject thiz, jstring rootfsPath, jstring nativeLibDir) {
    
    const char* path = env->GetStringUTFChars(rootfsPath, nullptr);
    if (!path) return -1;
    g_rootfs_path = path;
    env->ReleaseStringUTFChars(rootfsPath, path);
    
    const char* lib_dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    if (!lib_dir) return -1;
    g_native_lib_dir = lib_dir;
    env->ReleaseStringUTFChars(nativeLibDir, lib_dir);
    
    if (access(g_rootfs_path.c_str(), F_OK) != 0) {
        LOGE("Rootfs not found: %s", g_rootfs_path.c_str());
        return -2;
    }
    
    if (access((g_rootfs_path + "/bin/bash").c_str(), F_OK) != 0) {
        LOGE("bash not found");
        return -3;
    }
    
    g_initialized = true;
    LOGD("PRoot initialized: %s", g_rootfs_path.c_str());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeStartProcess(
    JNIEnv* env, jobject thiz, jstring command, jobjectArray args) {
    
    if (!g_initialized) return -1;
    
    const char* cmd = env->GetStringUTFChars(command, nullptr);
    if (!cmd) return -1;
    std::string command_str(cmd);
    env->ReleaseStringUTFChars(command, cmd);
    
    std::vector<std::string> args_vec;
    if (args) {
        jsize num_args = env->GetArrayLength(args);
        for (jsize i = 0; i < num_args; ++i) {
            jstring arg = (jstring)env->GetObjectArrayElement(args, i);
            const char* arg_str = env->GetStringUTFChars(arg, nullptr);
            if (arg_str) {
                args_vec.push_back(arg_str);
                env->ReleaseStringUTFChars(arg, arg_str);
            }
            env->DeleteLocalRef(arg);
        }
    }
    
    pid_t pid = fork();
    if (pid < 0) return -2;
    
    if (pid == 0) {
        std::string proot_path = get_proot_path();
        std::vector<std::string> exec_args;
        exec_args.push_back(proot_path);
        exec_args.push_back("-r");
        exec_args.push_back(g_rootfs_path);
        exec_args.push_back("-b"); exec_args.push_back("/proc");
        exec_args.push_back("-b"); exec_args.push_back("/dev");
        exec_args.push_back("-b"); exec_args.push_back("/sys");
        exec_args.push_back("-b"); exec_args.push_back(g_native_lib_dir + ":/usr/lib");
        exec_args.push_back("-w"); exec_args.push_back("/etc/hosts");
        exec_args.push_back("-p");
        
        // Add Android-specific environment variables for PRoot
        clearenv();
        setenv("HOME", "/home/user", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
        setenv("TERM", "xterm-256color", 1);
        
        // Critical PRoot environment variables
        std::string tmp_dir = g_rootfs_path + "/tmp";
        setenv("PROOT_TMP_DIR", tmp_dir.c_str(), 1);
        setenv("PROOT_LOADER", proot_path.c_str(), 1);
        
        // GLIBC_TUNABLES for glibc 2.34+ to avoid crashes
        setenv("GLIBC_TUNABLES", "glibc.rtld.dynamic_sort=1", 1);
        
        // Optional: disable seccomp if having issues (can be enabled for better performance)
        // exec_args.push_back("--no-seccomp");
        
        exec_args.push_back("--");
        exec_args.push_back(command_str);
        for (const auto& a : args_vec) exec_args.push_back(a);
        
        char** argv = vector_to_argv(exec_args);
        
        execvp(proot_path.c_str(), argv);
        _exit(127);
    }
    
    g_current_pid.store(pid);
    LOGD("Started PRoot PID: %d", pid);
    return pid;
}

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeStopProcess(JNIEnv*, jobject, jint pid) {
    if (pid <= 0) return -1;
    kill(-pid, SIGTERM);
    usleep(100000);
    kill(-pid, SIGKILL);
    waitpid(pid, nullptr, WNOHANG);
    g_current_pid.store(-1);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeExecute(
    JNIEnv* env, jobject thiz, jstring command, jobject outputCallback) {
    
    if (!g_initialized) return -1;
    
    const char* cmd = env->GetStringUTFChars(command, nullptr);
    if (!cmd) return -2;
    
    std::string proot_cmd = get_proot_path() + " -r " + g_rootfs_path +
                           " -b /proc -b /dev -b /sys" +
                           " -w /etc/hosts -p -- " + std::string(cmd);
    
    int exit_code = execute_command(proot_cmd.c_str(), outputCallback, env);
    env->ReleaseStringUTFChars(command, cmd);
    return exit_code;
}

JNIEXPORT jboolean JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeCheckAvailability(JNIEnv*, jobject) {
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeGetCurrentPid(JNIEnv*, jobject) {
    return g_current_pid.load();
}

JNIEXPORT jboolean JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeIsInitialized(JNIEnv*, jobject) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_avscode_runtime_PRootRuntime_nativeCleanup(JNIEnv*, jobject) {
    int pid = g_current_pid.load();
    if (pid > 0) {
        kill(-pid, SIGKILL);
        waitpid(pid, nullptr, WNOHANG);
    }
    g_current_pid.store(-1);
    g_rootfs_path.clear();
    g_native_lib_dir.clear();
    g_initialized = false;
}

} // extern "C"
