#!/usr/bin/env bash  
#
# Startup script for jetty under *nix systems (it works under NT/cygwin too).

# To get the service to restart correctly on reboot, uncomment below (3 lines):
# ========================
# chkconfig: 3 99 99
# description: Jetty 9 webserver
# processname: jetty
# ========================

# Configuration files
#
# /etc/default/jetty
#   If it exists, this is read at the start of script. It may perform any 
#   sequence of shell commands, like setting relevant environment variables.
#
# $HOME/.jettyrc
#   If it exists, this is read at the start of script. It may perform any 
#   sequence of shell commands, like setting relevant environment variables.
#
# /etc/jetty.conf
#   If found, and no configurations were given on the command line,
#   the file will be used as this script's configuration. 
#   Each line in the file may contain:
#     - A comment denoted by the pound (#) sign as first non-blank character.
#     - The path to a regular file, which will be passed to jetty as a 
#       config.xml file.
#     - The path to a directory. Each *.xml file in the directory will be
#       passed to jetty as a config.xml file.
#
#   The files will be checked for existence before being passed to jetty.
#
# $JETTY_HOME/etc/jetty.xml
#   If found, used as this script's configuration file, but only if
#   /etc/jetty.conf was not present. See above.
#   
# Configuration variables
#
# JAVA
#   Command to invoke Java. If not set, java (from the PATH) will be used.
#
# JAVA_OPTIONS
#   Extra options to pass to the JVM
#
# JETTY_HOME
#   Where Jetty is installed. If not set, the script will try go
#   guess it by first looking at the invocation path for the script,
#   and then by looking in standard locations as $HOME/opt/jetty
#   and /opt/jetty. The java system property "jetty.home" will be
#   set to this value for use by configure.xml files, f.e.:
#
#    <Arg><Property name="jetty.home" default="."/>/webapps/jetty.war</Arg>
#
# JETTY_PORT (Deprecated - use JETTY_ARGS)
#   Override the default port for Jetty servers. If not set then the
#   default value in the xml configuration file will be used. The java
#   system property "jetty.port" will be set to this value for use in
#   configure.xml files. For example, the following idiom is widely
#   used in the demo config files to respect this property in Listener
#   configuration elements:
#
#    <Set name="Port"><Property name="jetty.port" default="8080"/></Set>
#
#   Note: that the config file could ignore this property simply by saying:
#
#    <Set name="Port">8080</Set>
#
# JETTY_RUN
#   Where the jetty.pid file should be stored. It defaults to the
#   first available of /var/run, /usr/var/run, JETTY_HOME and /tmp 
#   if not set.
#  
# JETTY_PID
#   The Jetty PID file, defaults to $JETTY_RUN/jetty.pid
#   
# JETTY_ARGS
#   The default arguments to pass to jetty.
#   For example
#      JETTY_ARGS=jetty.port=8080 jetty.spdy.port=8443 jetty.secure.port=443
#
# JETTY_USER
#   if set, then used as a username to run the server as
#

usage()
{
    echo "Usage: ${0##*/} [-d] {start|stop|run|restart|check|supervise} [ CONFIGS ... ] "
    exit 1
}

[ $# -gt 0 ] || usage


##################################################
# Set the name
##################################################
JETTY=jetty


##################################################
# Some utility functions
##################################################
findDirectory()
{
  local L OP=$1
  shift
  for L in "$@"; do
    [ "$OP" "$L" ] || continue 
    printf %s "$L"
    break
  done 
}

running()
{
  local PID=$(cat "$1" 2>/dev/null) || return 1
  kill -0 "$PID" 2>/dev/null
}

started()
{
  # wait for 60s to see "STARTED" in PID file, needs jetty-started.xml as argument
  for T in 1 2 3 4 5 6 7 9 10 11 12 13 14 15 
  do
    sleep 4
    [ -z "$(grep STARTED $1 2>/dev/null)" ] || return 0
    [ -z "$(grep STOPPED $1 2>/dev/null)" ] || return 1
    [ -z "$(grep FAILED $1 2>/dev/null)" ] || return 1
    local PID=$(cat "$2" 2>/dev/null) || return 1
    kill -0 "$PID" 2>/dev/null || return 1
    echo -n ". "
  done

  return 1;
}


readConfig()
{
  (( DEBUG )) && echo "Reading $1.."
  source "$1"
}



##################################################
# Get the action & configs
##################################################
CONFIGS=()
NO_START=0
DEBUG=0

while [[ $1 = -* ]]; do
  case $1 in
    -d) DEBUG=1 ;;
  esac
  shift
done
ACTION=$1
shift

##################################################
# Read any configuration files
##################################################
ETC=/etc
if [ $UID != 0 ]
then 
  ETC=$HOME/etc
fi

for CONFIG in $ETC/default/${JETTY}{,9} $HOME/.${JETTY}rc; do
  if [ -f "$CONFIG" ] ; then 
    readConfig "$CONFIG"
  fi
done


##################################################
# Set tmp if not already set.
##################################################
TMPDIR=${TMPDIR:-/tmp}

##################################################
# Jetty's hallmark
##################################################
JETTY_INSTALL_TRACE_FILE="start.jar"


##################################################
# Try to determine JETTY_HOME if not set
##################################################
if [ -z "$JETTY_HOME" ] 
then
  JETTY_SH=$0
  case "$JETTY_SH" in
    /*)     JETTY_HOME=${JETTY_SH%/*/*} ;;
    ./*/*)  JETTY_HOME=${JETTY_SH%/*/*} ;;
    ./*)    JETTY_HOME=.. ;;
    */*/*)  JETTY_HOME=./${JETTY_SH%/*/*} ;;
    */*)    JETTY_HOME=. ;;
    *)      JETTY_HOME=.. ;;
  esac

  if [ ! -f "$JETTY_HOME/$JETTY_INSTALL_TRACE_FILE" ]
  then 
    JETTY_HOME=
  fi
fi


##################################################
# No JETTY_HOME yet? We're out of luck!
##################################################
if [ -z "$JETTY_HOME" ]; then
  echo "** ERROR: JETTY_HOME not set, you need to set it or install in a standard location" 
  exit 1
fi

cd "$JETTY_HOME"
JETTY_HOME=$PWD


##################################################
# Set JETTY_BASE 
##################################################
if [ -z "$JETTY_BASE" ]; then
  JETTY_BASE=$JETTY_HOME
fi

cd "$JETTY_BASE"
JETTY_BASE=$PWD


#####################################################
# Check that jetty is where we think it is
#####################################################
if [ ! -r "$JETTY_HOME/$JETTY_INSTALL_TRACE_FILE" ] 
then
  echo "** ERROR: Oops! Jetty doesn't appear to be installed in $JETTY_HOME"
  echo "** ERROR:  $JETTY_HOME/$JETTY_INSTALL_TRACE_FILE is not readable!"
  exit 1
fi

##################################################
# Try to find this script's configuration file,
# but only if no configurations were given on the
# command line.
##################################################
if [ -z "$JETTY_CONF" ] 
then
  if [ -f $ETC/${JETTY}.conf ]
  then
    JETTY_CONF=$ETC/${JETTY}.conf
  elif [ -f "$JETTY_BASE/etc/jetty.conf" ]
  then
    JETTY_CONF=$JETTY_BASE/etc/jetty.conf
  elif [ -f "$JETTY_HOME/etc/jetty.conf" ]
  then
    JETTY_CONF=$JETTY_HOME/etc/jetty.conf
  fi
fi

##################################################
# Get the list of config.xml files from jetty.conf
##################################################
if [ -z "$CONFIGS" ] && [ -f "$JETTY_CONF" ] && [ -r "$JETTY_CONF" ] 
then
  while read -r CONF
  do
    if expr "$CONF" : '#' >/dev/null ; then
      continue
    fi

    if [ -d "$CONF" ] 
    then
      # assume it's a directory with configure.xml files
      # for example: /etc/jetty.d/
      # sort the files before adding them to the list of CONFIGS
      for XMLFILE in "$CONF/"*.xml
      do
        if [ -r "$XMLFILE" ] && [ -f "$XMLFILE" ] 
        then
          CONFIGS+=("$XMLFILE")
        else
          echo "** WARNING: Cannot read '$XMLFILE' specified in '$JETTY_CONF'" 
        fi
      done
    else
      # assume it's a command line parameter (let start.jar deal with its validity)
      CONFIGS+=("$CONF")
    fi
  done < "$JETTY_CONF"
fi

#####################################################
# Find a location for the pid file
#####################################################
if [ -z "$JETTY_RUN" ] 
then
  JETTY_RUN=$(findDirectory -w /var/run /usr/var/run $JETTY_HOME /tmp)
fi

#####################################################
# Find a pid and state file
#####################################################
if [ -z "$JETTY_PID" ] 
then
  JETTY_PID="$JETTY_RUN/${JETTY}.pid"
fi

if [ -z "$JETTY_STATE" ] 
then
  JETTY_STATE=$JETTY_HOME/${JETTY}.state
fi
JAVA_OPTIONS+=("-Djetty.state=$JETTY_STATE")
rm -f $JETTY_STATE

##################################################
# Setup JAVA if unset
##################################################
if [ -z "$JAVA" ]
then
  JAVA=$(which java)
fi

if [ -z "$JAVA" ]
then
  echo "Cannot find a Java JDK. Please set either set JAVA or put java (>=1.5) in your PATH." 2>&2
  exit 1
fi

#####################################################
# See if JETTY_PORT is defined
#####################################################
if [ "$JETTY_PORT" ] 
then
  JETTY_ARGS+=("jetty.port=$JETTY_PORT")
fi

#####################################################
# See if JETTY_LOGS is defined
#####################################################
if [ -z "$JETTY_LOGS" ] && [ -d $JETTY_BASE/logs ] 
then
  JETTY_LOGS=$JETTY_BASE/logs
fi
if [ -z "$JETTY_LOGS" ] && [ -d $JETTY_HOME/logs ] 
then
  JETTY_LOGS=$JETTY_HOME/logs
fi
if [ "$JETTY_LOGS" ]
then
  JAVA_OPTIONS+=("-Djetty.logs=$JETTY_LOGS")
fi

#####################################################
# Are we running on Windows? Could be, with Cygwin/NT.
#####################################################
case "`uname`" in
CYGWIN*) PATH_SEPARATOR=";";;
*) PATH_SEPARATOR=":";;
esac


#####################################################
# Add jetty properties to Java VM options.
#####################################################
JAVA_OPTIONS+=("-Djetty.home=$JETTY_HOME" "-Djetty.base=$JETTY_BASE" "-Djava.io.tmpdir=$TMPDIR")

[ -f "$JETTY_HOME/etc/start.config" ] && JAVA_OPTIONS=("-DSTART=$JETTY_HOME/etc/start.config" "${JAVA_OPTIONS[@]}")

#####################################################
# This is how the Jetty server will be started
#####################################################

JETTY_START=$JETTY_HOME/start.jar
[ ! -f "$JETTY_START" ] && JETTY_START=$JETTY_HOME/lib/start.jar

START_INI=$(dirname $JETTY_START)/start.ini
[ -r "$START_INI" ] || START_INI=""

RUN_ARGS=(${JAVA_OPTIONS[@]} -jar "$JETTY_START" $JETTY_ARGS "${CONFIGS[@]}")
RUN_CMD=("$JAVA" ${RUN_ARGS[@]})

#####################################################
# Comment these out after you're happy with what 
# the script is doing.
#####################################################
if (( DEBUG ))
then
  echo "JETTY_HOME     =  $JETTY_HOME"
  echo "JETTY_BASE     =  $JETTY_BASE"
  echo "JETTY_CONF     =  $JETTY_CONF"
  echo "JETTY_PID      =  $JETTY_PID"
  echo "JETTY_START    =  $JETTY_START"
  echo "JETTY_ARGS     =  $JETTY_ARGS"
  echo "CONFIGS        =  ${CONFIGS[*]}"
  echo "JAVA_OPTIONS   =  ${JAVA_OPTIONS[*]}"
  echo "JAVA           =  $JAVA"
  echo "RUN_CMD        =  ${RUN_CMD}"
fi

##################################################
# Do the action
##################################################
case "$ACTION" in
  start)
    echo -n "Starting Jetty: "

    if (( NO_START )); then 
      echo "Not starting ${JETTY} - NO_START=1";
      exit
    fi

    if [ $UID -eq 0 ] && type start-stop-daemon > /dev/null 2>&1 
    then
      unset CH_USER
      if [ -n "$JETTY_USER" ]
      then
        CH_USER="-c$JETTY_USER"
      fi

      start-stop-daemon -S -p"$JETTY_PID" $CH_USER -d"$JETTY_HOME" -b -m -a "$JAVA" -- "${RUN_ARGS[@]}" --daemon

    else

      if [ -f "$JETTY_PID" ]
      then
        if running $JETTY_PID
        then
          echo "Already Running!"
          exit 1
        else
          # dead pid file - remove
          rm -f "$JETTY_PID"
        fi
      fi

      if [ "$JETTY_USER" ] 
      then
        touch "$JETTY_PID"
        chown "$JETTY_USER" "$JETTY_PID"
        # FIXME: Broken solution: wordsplitting, pathname expansion, arbitrary command execution, etc.
        su - "$JETTY_USER" -c "
          exec ${RUN_CMD[*]} --daemon &
          disown \$!
          echo \$! > '$JETTY_PID'"
      else
        "${RUN_CMD[@]}" &
        disown $!
        echo $! > "$JETTY_PID"
      fi

    fi

    if expr "${CONFIGS[*]}" : '.*etc/jetty-started.xml.*' >/dev/null
    then
      if started "$JETTY_STATE" "$JETTY_PID"
      then
        echo "OK `date`"
      else
        echo "FAILED `date`"
      fi
    else
      echo "ok `date`"
    fi

    ;;

  stop)
    echo -n "Stopping Jetty: "
    if [ $UID -eq 0 ] && type start-stop-daemon > /dev/null 2>&1; then
      start-stop-daemon -K -p"$JETTY_PID" -d"$JETTY_HOME" -a "$JAVA" -s HUP
      
      TIMEOUT=30
      while running "$JETTY_PID"; do
        if (( TIMEOUT-- == 0 )); then
          start-stop-daemon -K -p"$JETTY_PID" -d"$JETTY_HOME" -a "$JAVA" -s KILL
        fi

        sleep 1
      done

      rm -f "$JETTY_PID"
      echo OK
    else
      PID=$(cat "$JETTY_PID" 2>/dev/null)
      kill "$PID" 2>/dev/null
      
      TIMEOUT=30
      while running $JETTY_PID; do
        if (( TIMEOUT-- == 0 )); then
          kill -KILL "$PID" 2>/dev/null
        fi

        sleep 1
      done

      rm -f "$JETTY_PID"
      echo OK
    fi

    ;;

  restart)
    JETTY_SH=$0
    if [ ! -f $JETTY_SH ]; then
      if [ ! -f $JETTY_HOME/bin/jetty.sh ]; then
        echo "$JETTY_HOME/bin/jetty.sh does not exist."
        exit 1
      fi
      JETTY_SH=$JETTY_HOME/bin/jetty.sh
    fi

    "$JETTY_SH" stop "$@"
    "$JETTY_SH" start "$@"

    ;;

  supervise)
    #
    # Under control of daemontools supervise monitor which
    # handles restarts and shutdowns via the svc program.
    #
    exec "${RUN_CMD[@]}"

    ;;

  run|demo)
    echo "Running Jetty: "

    if [ -f "$JETTY_PID" ]
    then
      if running "$JETTY_PID"
      then
        echo "Already Running!"
        exit 1
      else
        # dead pid file - remove
        rm -f "$JETTY_PID"
      fi
    fi

    exec "${RUN_CMD[@]}"
    ;;

  check|status)
    echo "Checking arguments to Jetty: "
    echo "START_INI      =  $START_INI"
    echo "JETTY_HOME     =  $JETTY_HOME"
    echo "JETTY_BASE     =  $JETTY_BASE"
    echo "JETTY_CONF     =  $JETTY_CONF"
    echo "JETTY_PID      =  $JETTY_PID"
    echo "JETTY_START    =  $JETTY_START"
    echo "JETTY_LOGS     =  $JETTY_LOGS"
    echo "CONFIGS        =  ${CONFIGS[*]}"
    echo "CLASSPATH      =  $CLASSPATH"
    echo "JAVA           =  $JAVA"
    echo "JAVA_OPTIONS   =  ${JAVA_OPTIONS[*]}"
    echo "JETTY_ARGS     =  $JETTY_ARGS"
    echo "RUN_CMD        =  ${RUN_CMD[*]}"
    echo
    
    if [ -f "$JETTY_PID" ]
    then
      echo "Jetty running pid=$(< "$JETTY_PID")"
      exit 0
    fi
    exit 1

    ;;

  *)
    usage

    ;;
esac

exit 0
