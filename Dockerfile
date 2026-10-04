FROM gradle:8.13-jdk17 AS gradle-dist
FROM python:3.11-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless git curl unzip ca-certificates make gcc g++ \
    && mkdir -p /opt/java /home/gradle \
    && ln -s "$(dirname "$(dirname "$(readlink -f /usr/bin/javac)")")" /opt/java/openjdk \
    && rm -rf /var/lib/apt/lists/*

COPY --from=gradle-dist /opt/gradle /opt/gradle
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/gradle/bin:${JAVA_HOME}/bin:${PATH}

ENV ANDROID_CMDLINE_TOOLS_VERSION=15859902
ENV ANDROID_SDK_URL=https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip

ENV ANDROID_HOME=/usr/local/android-sdk-linux

ENV ANDROID_API_LEVEL=android-36
ENV ANDROID_VERSION=36
ENV ANDROID_BUILD_TOOLS_VERSION=36.0.0

ENV ANDROID_NDK_VERSION=26.3.11579264
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools
ENV PATH=${PATH}:${ANDROID_NDK_HOME}
ENV PATH=${PATH}:${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /home/gradle/.android && \
    cd /tmp && \
    curl -fL "${ANDROID_SDK_URL}" -o commandlinetools.zip && \
    unzip commandlinetools.zip && \
    mv cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm commandlinetools.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses
RUN sdkmanager \
    --sdk_root="${ANDROID_HOME}" \
    "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
    "platforms;android-${ANDROID_VERSION}" \
    "platform-tools" \
    "ndk;${ANDROID_NDK_VERSION}" \
    "cmake;3.22.1"

CMD mkdir -p /home/source/TMessagesProj/build/outputs/apk && \
    cp -R /home/source/. /home/gradle && \
    cd /home/gradle && \
    ./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone --stacktrace && \
    cp -R /home/gradle/TMessagesProj_AppStandalone/build/outputs/apk/. /home/source/TMessagesProj/build/outputs/apk
