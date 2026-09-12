#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "AvscodeSpawn"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void close_pair(int pair[2]) {
    close(pair[0]);
    close(pair[1]);
}

static jintArray native_spawn_impl(JNIEnv *env, jobjectArray java_argv,
                                   jobjectArray java_env, jstring java_cwd,
                                   jstring java_output) {
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        free(argv);
        free(envp);
        return NULL;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);
    const char *output_utf = (*env)->GetStringUTFChars(env, java_output, NULL);
    char *output_path = strdup(output_utf);
    (*env)->ReleaseStringUTFChars(env, java_output, output_utf);

    int in_pipe[2];
    if (pipe(in_pipe) != 0) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(argv); free(envp); free(cwd); free(output_path);
        return NULL;
    }

    pid_t pid = fork();
    if (pid == 0) {
        // Give every runtime launch its own process group so stopping the wrapper
        // also stops all guest processes spawned underneath it.
        setpgid(0, 0);
        close(in_pipe[1]);
        int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
        if (output_fd < 0) {
            dprintf(STDERR_FILENO, "Failed to open output file %s: %s\n", output_path, strerror(errno));
            _exit(126);
        }
        dup2(in_pipe[0], STDIN_FILENO);
        dup2(output_fd, STDOUT_FILENO);
        dup2(output_fd, STDERR_FILENO);
        close(in_pipe[0]);
        close(output_fd);
        if (chdir(cwd) != 0) {
            dprintf(STDERR_FILENO, "Failed to chdir to %s: %s\n", cwd, strerror(errno));
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "Native exec failed (%s): %s\n", argv[0], strerror(errno));
        _exit(127);
    }

    if (pid > 0) {
        setpgid(pid, pid);
    }
    close(in_pipe[0]);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd); free(output_path);

    if (pid < 0) {
        close_pair(in_pipe);
        return NULL;
    }
    jint values[2] = {pid, in_pipe[1]};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

static jint native_wait_for_impl(jint pid, jboolean no_hang) {
    int status = 0;
    pid_t value = waitpid(pid, &status, no_hang ? WNOHANG : 0);
    if (value == 0) return -2; // Still running
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

static jint native_kill_impl(jint pid, jint signal) {
    // Negative pid targets the whole process group.
    int result = kill(-pid, signal);
    if (result != 0 && errno == ESRCH) {
        result = kill(pid, signal);
    }
    return result;
}

// JNI exports for com.avscode.runtime.NativeSpawn
JNIEXPORT jintArray JNICALL
Java_com_avscode_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                           jobjectArray java_env, jstring java_cwd,
                                           jstring java_output) {
    (void)self;
    return native_spawn_impl(env, java_argv, java_env, java_cwd, java_output);
}

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    return native_wait_for_impl(pid, no_hang);
}

JNIEXPORT jint JNICALL
Java_com_avscode_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    return native_kill_impl(pid, signal);
}

// Backwards compatibility JNI exports for com.linuxdroid.core.process.NativeSpawn
JNIEXPORT jintArray JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                   jobjectArray java_env, jstring java_cwd,
                                                   jstring java_output) {
    (void)self;
    return native_spawn_impl(env, java_argv, java_env, java_cwd, java_output);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    return native_wait_for_impl(pid, no_hang);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_core_process_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    return native_kill_impl(pid, signal);
}

