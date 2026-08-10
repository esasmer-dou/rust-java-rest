#!/usr/bin/env sh
set -eu

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <mode> <artifact> <output> [group] [package] [port] [version]" >&2
  exit 2
fi

mode=$1
artifact=$2
output=$3
group=${4:-com.example}
package_name=${5:-}
port=${6:-8080}
version=${7:-4.2.0}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
checkout_jar="$script_dir/../target/rust-java-rest-$version-codegen.jar"
if [ -f "$checkout_jar" ]; then
  jar=$checkout_jar
else
  local_repository=$(mvn -q help:evaluate \
    "-Dexpression=settings.localRepository" "-DforceStdout")
  if [ -z "$local_repository" ]; then
    echo "Could not determine the Maven local repository from settings.xml." >&2
    exit 1
  fi
  repository_jar="$local_repository/com/reactor/rust-java-rest/$version/rust-java-rest-$version-codegen.jar"
  jar=$repository_jar
fi

case "$mode" in
  rest|cache-reader|cache-writer|dubbo-static|dubbo-zookeeper) ;;
  *) echo "unsupported mode: $mode" >&2; exit 2 ;;
esac

if [ ! -f "$jar" ]; then
  (
    cd "${TMPDIR:-/tmp}"
    mvn -q dependency:get "-Dartifact=com.reactor:rust-java-rest:$version:jar:codegen"
  )
  if [ ! -f "$repository_jar" ]; then
    echo "Maven completed without installing $repository_jar. Run 'mvn install' in rust-java-rest or publish the codegen classifier." >&2
    exit 1
  fi
  jar=$repository_jar
fi

if [ -n "$package_name" ]; then
  exec java -cp "$jar" com.reactor.rust.codegen.ProjectGenerator \
    --mode "$mode" --artifact "$artifact" --output "$output" \
    --group "$group" --package "$package_name" --port "$port"
fi

exec java -cp "$jar" com.reactor.rust.codegen.ProjectGenerator \
  --mode "$mode" --artifact "$artifact" --output "$output" \
  --group "$group" --port "$port"
