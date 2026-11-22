# source this: . ./env.sh
export JAVA_HOME=$(ls -d $HOME/.local/jdk/jdk-17*/ | head -1)
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
