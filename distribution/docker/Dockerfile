############################################################
# Dockerfile to run an OrientDB (Graph) Container
# for current developed version
############################################################

FROM azul/zulu-openjdk:11

MAINTAINER OrientDB LTD (info@orientdb.com)
# ARG IN_SOURCE_PATH
RUN mkdir /orientdb

# COPY ${IN_SOURCE_PATH} /orientdb-community.tar.gz
COPY orientdb-community-*.tar.gz /orientdb-community.tar.gz

RUN apt update \
    && apt install -y curl wget \
    && rm -rf /var/lib/apt/lists/*

RUN tar -xvzf orientdb-community.tar.gz -C /orientdb --strip-components=1\
      && rm orientdb-community.tar.gz

COPY "agent-3.2.22.jar" "/orientdb/plugins/agent-3.2.22.jar"

ENV PATH /orientdb/bin:$PATH

VOLUME ["/orientdb/backup", "/orientdb/databases", "/orientdb/config"]

WORKDIR /orientdb
RUN addgroup --system --gid 1000 system && adduser --system --uid 1000 --gid 1000 --no-create-home orientdb

RUN chmod ugo+rwx /orientdb $JAVA_HOME
RUN chown -R 1000:1000 /orientdb

USER orientdb

#OrientDb binary
EXPOSE 2424

#OrientDb http
EXPOSE 2480

# Default command start the server
CMD ["server.sh"]

