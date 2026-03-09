#base image : custom bir image 
FROM 10.21.21.119/pethevean/petheaven-pet-api-jre21:1.0.0 AS builder

USER 0

WORKDIR source

ARG JAR_FILE=*.jar

COPY ${JAR_FILE} application.jar

RUN java -Djarmode=layertools -jar application.jar extract

RUN rsync -a application/* ./
RUN rsync -a dependencies/* ./
RUN rsync -a spring-boot-loader/* ./
# bu arkadasin ici bos
RUN rsync -a --ignore-missing-args snapshot-dependencies/* ./
RUN true
COPY librust_hyper.so rust_hyper.so
RUN true

RUN /bin/sh -c 'java -Xquickstart -Xscmx30M -Xshareclasses:name=openj9cache,cacheDir=openj9cache org.springframework.boot.loader.launch.JarLauncher &' ; sleep 15 ; ps aux | grep java | grep springframework | awk '{print $2}' | pkill -9 -f springframework

RUN true
RUN sed -i -e 's/BOOT-INF/NEW_BOOT-INF/g' BOOT-INF/classpath.idx
RUN true
RUN rm -rf BOOT-INF/lib
RUN true
RUN rm -rf BOOT-INF/classes/container
RUN true

FROM 10.21.21.119/pethevean/petheaven-pet-api-jre21:1.1.0

ARG APP_HOME=/u01/applications/petheaven-spring-rust

USER 0

RUN mkdir -p /app/native
RUN mkdir -p ${APP_HOME}/glowroot-config
RUN mkdir -p ${APP_HOME}/glowroot-tmp
RUN mkdir -p ${APP_HOME}/glowroot-log
RUN mkdir -p ${APP_HOME}/cache
RUN mkdir -p ${APP_HOME}/BOOT-INF
RUN mkdir -p ${APP_HOME}/META-INF
RUN mkdir -p ${APP_HOME}/org

WORKDIR ${APP_HOME}


# agent config.json
COPY config.json /u01/applications/petheaven-spring-rust/glowroot-config/

COPY --from=builder source/rust_hyper.so /app/native/rust_hyper.so
COPY --from=builder source/openj9cache ./cache
COPY --from=builder source/BOOT-INF ./BOOT-INF
COPY --from=builder source/META-INF ./META-INF
COPY --from=builder source/org ./org
RUN true
COPY --from=builder source/application/ ./
RUN true


# tune glibc memory allocation, optimize for low fragmentation
# limit the number of arenas
# requires glibc >= 2.16 since there was a bug in 
# MALLOC_ARENA_TEST parameter handling that cause MALLOC_ARENA_MAX not to work

ENV MALLOC_ARENA_MAX=1

# disable dynamic mmap threshold, see M_MMAP_THRESHOLD in "man mallopt"

# ENV MALLOC_MMAP_THRESHOLD_=131072
# ENV MALLOC_TRIM_THRESHOLD_=131072
# ENV MALLOC_TOP_PAD_=131072
# ENV MALLOC_MMAP_MAX_=65536


RUN sed -i -e 's/BOOT-INF/NEW_BOOT-INF/g' BOOT-INF/classpath.idx
RUN true

# RUN apt-get remove -y procps && apt-get -y remove rsync && rm -rf /var/lib/apt/lists/*

# CMD ["sleep", "10000"]