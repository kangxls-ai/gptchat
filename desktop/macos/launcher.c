#include <limits.h>
#include <mach-o/dyld.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char path[PATH_MAX], absolute[PATH_MAX], java[PATH_MAX], jar[PATH_MAX];
    uint32_t capacity = sizeof(path);
    if (_NSGetExecutablePath(path, &capacity) != 0 || realpath(path, absolute) == NULL) {
        fputs("MyAI could not locate its application bundle.\n", stderr);
        return 1;
    }
    char *bundle = strstr(absolute, "/Contents/MacOS/MyAI");
    if (bundle == NULL || strcmp(bundle, "/Contents/MacOS/MyAI") != 0) {
        fputs("MyAI has an unexpected application bundle layout.\n", stderr);
        return 1;
    }
    *bundle = '\0';
    int java_length = snprintf(java, sizeof(java), "%s/Contents/runtime/Contents/Home/bin/java", absolute);
    int jar_length = snprintf(jar, sizeof(jar), "%s/Contents/Resources/myai-desktop.jar", absolute);
    if (java_length < 0 || (size_t)java_length >= sizeof(java)
            || jar_length < 0 || (size_t)jar_length >= sizeof(jar)) return 1;

    char **arguments = calloc((size_t)argc + 4, sizeof(*arguments));
    if (arguments == NULL) return 1;
    arguments[0] = java;
    arguments[1] = "-Xdock:name=MyAI";
    arguments[2] = "-jar";
    arguments[3] = jar;
    for (int i = 1; i < argc; i++) arguments[i + 3] = argv[i];
    execv(java, arguments);
    perror("MyAI could not start its Java runtime");
    free(arguments);
    return 1;
}
