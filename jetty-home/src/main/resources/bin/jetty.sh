#!/usr/bin/env bash

# LSB Tags
### BEGIN INIT INFO
# Provides:          jetty
# Required-Start:    $local_fs $network
# Required-Stop:     $local_fs $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Jetty start script.
# Description:       Start Jetty web server.
### END INIT INFO

# Startup script for jetty under *nix systems (it works under NT/cygwin too).

##################################################
# Set the name which is used by other variables.
# Defaults to the file name without extension.
##################################################
NAME=$(echo $(basename $0) | sed -e 's/^[SK][0-9]*//' -e 's/\.sh$//')

# To get the service to restart correctly on reboot, uncomment below (3 lines):
# ========================
# chkconfig: 3 99 99
# description: Eclipse Jetty webserver
# processname: jetty
# ========================

# Configuration files
#
# /etc/default/$NAME
#   If it exists, this is read at the start of script. It may perform any
#   sequence of shell commands, like setting relevant environment variables.
#
# $HOME/.$NAMErc (e.g. $HOME/.jettyrc)
#   If it exists, this is read at the start of script. It may perform any
#   sequence of shell commands, like setting relevant environment variables.
#
# /etc/$NAME.conf
#   If found, and no configurations were given on the command line,
#   the file will be used as this script's configuration.
#   Each line in the file may contain:
#     - A comment denoted by the pound (#) sign as first non-blank character.
#     - The path to a regular file, which will be passed to jetty as a
#       config.xml file.
#     - The path to a directory. Each *.xml file in the directory will be
#       passed to jetty as a config.xml file.
#     - All other lines will be passed, as-is to the start.jar
#
#   The files will be checked for existence before being passed to jetty.
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
#   guess it by looking at the invocation path for the script
#   The java system property "jetty.home" will be
#   set to this value for use by configure.xml files, f.e.:
#
#    <Arg><Property name="jetty.home" default="."/>/webapps/jetty.war</Arg>
#
# JETTY_BASE
#   Where your Jetty base directory is.  If not set, then the currently
#   directory is checked, otherwise the value from
#   $JETTY_HOME will be used.
#
# JETTY_RUN
#   Where the $NAME.pid file should be stored. It defaults to the
#   first available of /var/run, /usr/var/run, JETTY_BASE and /tmp
#   if not set.
#
# JETTY_PID
#   The Jetty PID file, defaults to $JETTY_RUN/$NAME.pid
#
# JETTY_ARGS
#   The default arguments to pass to jetty.
#   For example
#      JETTY_ARGS=jetty.http.port=8080 jetty.ssl.port=8443
#
# JETTY_USER
#   if set, then used as a username to run the server as
#
# JETTY_SHELL
#   If set, then used as the shell by su when starting the server.  Will have
#   no effect if start-stop-daemon exists.  Useful when JETTY_USER does not
#   have shell access, e.g. /bin/false
#
# JETTY_START_TIMEOUT
#   Time spent waiting to see if startup was successful/failed. Defaults to 60 seconds
#

usage()
{
    echo "Usage: ${0##*/} [-d] {start|stop|run|restart|check|supervise} [ CONFIGS ... ] "
    exit 1
}

[ $# -gt 0 ] || usage


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

# test if process specified in PID file is still running
running()
{
  local PIDFILE=$1
  if [ -r "$PIDFILE" ] ; then
    local PID=$(tail -1 "$PIDFILE")
    if kill -0 "$PID" 2>/dev/null ; then
      return 0
    fi
  fi
  return 1
}

# Test state file (after timeout) for started state
started()
{
  local STATEFILE=$1
  local PIDFILE=$2
  local STARTTIMEOUT=$3

  if (( DEBUG )) ; then
    echo "Looking for $STATEFILE"
    echo -n "State Parent Directory: "
    ls -lad $(dirname $STATEFILE)
  fi

  # wait till timeout to see "STARTED" in state file, needs --module=state as argument
  for ((T = 0; T < $STARTTIMEOUT; T++))
  do
    echo -n "."
    sleep 1
    if [ -r $STATEFILE ] ; then
      STATENOW=$(tail -1 $STATEFILE)
      (( DEBUG )) && echo "State (now): $STATENOW"
      case "$STATENOW" in
        STARTED*)
          echo " started"
          return 0;;
        STOPPED*)
          echo " stopped"
          return 1;;
        FAILED*)
          echo " failed"
          return 1;;
      esac
    else
      (( DEBUG )) && echo "Unable to read State File: $STATEFILE"
    fi
  done
  (( DEBUG )) && echo "Timeout $STARTTIMEOUT expired waiting for start state from $STATEFILE"
  echo " timeout"
  if running "$PIDFILE" ; then
    echo "INFO: Server process is running"
  else
    echo "** ERROR: Server process is NOT running"
  fi
  return 1;
}

pidKill()
{
  local PIDFILE=$1
  local TIMEOUT=$2

  if [ -r $PIDFILE ] ; then
    local PID=$(tail -1 "$PIDFILE")
    if [ -z "$PID" ] ; then
      echo "** ERROR: no pid found in $PIDFILE"
      return 1
    fi

    # Try default kill first
    if kill -0 "$PID" 2>/dev/null ; then
      (( DEBUG )) && echo "PID=$PID is running, sending kill"
      kill "$PID" 2>/dev/null
    else
      rm -f $PIDFILE 2> /dev/null
      return 0
    fi

    # Perform harsh kill next
    while kill -0 "$PID" 2>/dev/null
    do
      if (( TIMEOUT-- == 0 )) ; then
        (( DEBUG )) && echo "PID=$PID is running, sending kill signal=KILL (TIMEOUT=$TIMEOUT)"
        kill -KILL "$PID" 2>/dev/null
      fi
      echo -n "."
      sleep 1
    done
    echo "Killed $PID"
    return 0
  else
    (( DEBUG )) && echo "Unable to read PID File: $PIDFILE"
    return 1
  fi
}

testFileSystemPermissions()
{
  # Don't test file system permissions if user is root
  if [ $UID -eq 0 ] ; then
    (( DEBUG )) && echo "Not testing file system permissions: uid is 0"
    return 0
  fi

  # Don't test if JETTY_USER is specified
  # as the Jetty process will switch to a different user id on startup
  if [ -n "$JETTY_USER" ] ; then
    (( DEBUG )) && echo "Not testing file system permissions: JETTY_USER=$JETTY_USER"
    return 0
  fi

  # Don't test if setuid is specified
  # as the Jetty process will switch to a different user id on startup
  if expr -- "${JETTY_ARGS[*]}" : '.*setuid.*' >/dev/null
  then
    (( DEBUG )) && echo "Not testing file system permissions: setuid in use"
    return 0
  fi

  # Test if PID can be written from this userid
  if ! touch "$JETTY_PID"
  then
    echo "** ERROR: Unable to touch file: $JETTY_PID"
    echo "          Correct issues preventing use of \$JETTY_PID and try again."
    exit 1
  fi

  # Test if STATE can be written from this userid
  if ! touch "$JETTY_STATE"
  then
    echo "** ERROR: Unable to touch file: $JETTY_STATE"
    echo "          Correct issues preventing use of \$JETTY_STATE and try again."
    exit 1
  fi
}

readConfig()
{
  (( DEBUG )) && echo "Reading $1.."
  source "$1"
}

dumpEnv()
{
  echo "JAVA                  =  $JAVA"
  echo "JAVA_OPTIONS          =  ${JAVA_OPTIONS[*]}"
  echo "JETTY_HOME            =  $JETTY_HOME"
  echo "JETTY_BASE            =  $JETTY_BASE"
  echo "START_D               =  $START_D"
  echo "START_INI             =  $START_INI"
  echo "JETTY_START           =  $JETTY_START"
  echo "JETTY_CONF            =  $JETTY_CONF"
  echo "JETTY_ARGS            =  ${JETTY_ARGS[*]}"
  echo "JETTY_RUN             =  $JETTY_RUN"
  echo "JETTY_PID             =  $JETTY_PID"
  echo "JETTY_START_LOG       =  $JETTY_START_LOG"
  echo "JETTY_STATE           =  $JETTY_STATE"
  echo "JETTY_START_TIMEOUT   =  $JETTY_START_TIMEOUT"
  echo "JETTY_SYS_PROPS       =  $JETTY_SYS_PROPS"
  echo "RUN_ARGS              =  ${RUN_ARGS[*]}"
  echo "ID                    =  $(id)"
  echo "JETTY_USER            =  $JETTY_USER"
  echo "USE_START_STOP_DAEMON =  $USE_START_STOP_DAEMON"
  echo "START_STOP_DAEMON     =  $START_STOP_DAEMON_AVAILABLE"
}


##################################################
# Get the action & configs
##################################################
CONFIGS=()
NO_START=0
DEBUG=0
USE_START_STOP_DAEMON=1

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

for CONFIG in {/etc,~/etc}/default/${NAME}{,9} $HOME/.${NAME}rc; do
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

RUN_DIR=$(pwd)
cd "$JETTY_HOME"
JETTY_HOME=$(pwd)

##################################################
# Set JETTY_BASE
##################################################
export JETTY_BASE
if [ -z "$JETTY_BASE" ]; then
  if [ -d "$RUN_DIR/start.d" -o -f "$RUN_DIR/start.ini" ]; then
    JETTY_BASE=$RUN_DIR
  else
    JETTY_BASE=$JETTY_HOME
  fi
fi
cd "$JETTY_BASE"
JETTY_BASE=$(pwd)

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
  if [ -f $ETC/${NAME}.conf ]
  then
    JETTY_CONF=$ETC/${NAME}.conf
  elif [ -f "$JETTY_BASE/etc/jetty.conf" ]
  then
    JETTY_CONF=$JETTY_BASE/etc/jetty.conf
  elif [ -f "$JETTY_HOME/etc/jetty.conf" ]
  then
    JETTY_CONF=$JETTY_HOME/etc/jetty.conf
  fi
fi

#####################################################
# Find a location for the pid file
#####################################################
if [ -z "$JETTY_RUN" ]
then
  JETTY_RUN=$(findDirectory -w /var/run /usr/var/run $JETTY_BASE /tmp)/jetty
fi

if [ ! -d "$JETTY_RUN" ] ; then
  if ! mkdir $JETTY_RUN
  then
    echo "** ERROR: Unable to create directory: $JETTY_RUN"
    echo "          Correct issues preventing the creation of \$JETTY_RUN and try again."
    exit 1
  fi
fi

#####################################################
# define start log location
#####################################################
if [ -z "$JETTY_START_LOG" ]
then
  JETTY_START_LOG="$JETTY_RUN/$NAME-start.log"
fi

#####################################################
# Find a pid and state file
#####################################################
if [ -z "$JETTY_PID" ]
then
  JETTY_PID="$JETTY_RUN/${NAME}.pid"
fi

if [ -z "$JETTY_STATE" ]
then
  JETTY_STATE=$JETTY_BASE/${NAME}.state
fi

case "`uname`" in
CYGWIN*) JETTY_STATE="`cygpath -w $JETTY_STATE`";;
esac

JETTY_ARGS=(${JETTY_ARGS[*]} "jetty.state=$JETTY_STATE" "jetty.pid=$JETTY_PID")

##################################################
# Get the list of config.xml files from jetty.conf
##################################################
if [ -f "$JETTY_CONF" ] && [ -r "$JETTY_CONF" ]
then
  (( DEBUG )) && echo "$JETTY_CONF: (begin read) JETTY_ARGS.length=${#JETTY_ARGS[@]}"
  while read -r CONF
  do
    if expr -- "$CONF" : '#' >/dev/null ; then
      continue
    fi

    if [ -d "$CONF" ]
    then
      # assume it's a directory with configure.xml files
      # for example: /etc/jetty.d/
      # sort the files before adding them to the list of JETTY_ARGS
      for XMLFILE in "$CONF/"*.xml
      do
        if [ -r "$XMLFILE" ] && [ -f "$XMLFILE" ]
        then
          JETTY_ARGS[${#JETTY_ARGS[@]}]=$XMLFILE
        else
          echo "** WARNING: Cannot read '$XMLFILE' specified in '$JETTY_CONF'"
        fi
      done
    else
      # assume it's a command line parameter (let start.jar deal with its validity)
      JETTY_ARGS[${#JETTY_ARGS[@]}]=$CONF
    fi
  done < "$JETTY_CONF"
  (( DEBUG )) && echo "$JETTY_CONF: (finished read) JETTY_ARGS.length=${#JETTY_ARGS[@]}"
fi

##################################################
# Setup JAVA if unset
##################################################
if [ -z "$JAVA" ]
then
  JAVA=$(which java)
fi

if [ -z "$JAVA" ]
then
  echo "Cannot find a Java JDK. Please set either set JAVA or put java (>=1.5) in your PATH." >&2
  exit 1
fi

#####################################################
# See if Deprecated JETTY_LOGS is defined
#####################################################
if [ "$JETTY_LOGS" ]
then
  echo "** WARNING: JETTY_LOGS is Deprecated. Please configure logging within the jetty base." >&2
fi

#####################################################
# Set STARTED timeout
#####################################################
if [ -z "$JETTY_START_TIMEOUT" ]
then
  JETTY_START_TIMEOUT=60
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

case "`uname`" in
CYGWIN*)
JETTY_HOME="`cygpath -w $JETTY_HOME`"
JETTY_BASE="`cygpath -w $JETTY_BASE`"
TMPDIR="`cygpath -w $TMPDIR`"
;;
esac

#####################################################
# This is how the Jetty server will be started
#####################################################

JETTY_START=$JETTY_HOME/start.jar
START_INI=$JETTY_BASE/start.ini
START_D=$JETTY_BASE/start.d
if [ ! -f "$START_INI" -a ! -d "$START_D" ]
then
  echo "Cannot find a start.ini file or a start.d directory in your JETTY_BASE directory: $JETTY_BASE" >&2
  exit 1
fi

case "`uname`" in
CYGWIN*) JETTY_START="`cygpath -w $JETTY_START`";;
esac

# Determine if we can use start-stop-daemon or not
START_STOP_DAEMON_AVAILABLE=0

if (( USE_START_STOP_DAEMON ))
then
  # only if root user is executing jetty.sh, and the start-stop-daemon exists
  if [ $UID -eq 0 ] && type start-stop-daemon > /dev/null 2>&1
  then
    START_STOP_DAEMON_AVAILABLE=1
  else
    USE_START_STOP_DAEMON=0
  fi
fi

# Collect the dry-run (of opts,path,main,args,envs) from the jetty.base configuration
JETTY_DRY_RUN=$(echo "${JETTY_ARGS[*]} ${JAVA_OPTIONS[*]}" | xargs "$JAVA" -jar "$JETTY_START" --dry-run=opts,path,main,args,envs)
RUN_ARGS=($JETTY_SYS_PROPS ${JETTY_DRY_RUN[@]})

if (( DEBUG ))
then
  if expr -- "${RUN_ARGS[*]}" : '.*/etc/console-capture.xml.*' > /dev/null
  then
    echo "WARNING: Disable console-capture module for best DEBUG results"
  fi
  echo "IDs are $(id)"
  dumpEnv
fi

##################################################
# Do the action
##################################################
case "$ACTION" in
  start)
    if (( NO_START )); then
      echo "Not starting ${NAME} - NO_START=1";
      exit
    fi

    testFileSystemPermissions

    if running $JETTY_PID
    then
      echo "Already Running $(cat $JETTY_PID)!"
      exit 1
    fi

    # remove any lingering state file
    if [ -f $JETTY_STATE ]
    then
      rm $JETTY_STATE
    fi

    echo -n "Starting Jetty: "

    # Startup from a service file
    if (( USE_START_STOP_DAEMON ))
    then
      unset CH_USER
      if [ -n "$JETTY_USER" ]
      then
        CH_USER="--chuid $JETTY_USER"
      fi

      # use of --pidfile /dev/null disables internal pidfile
      # management of the start-stop-daemon (see man page)
      echo ${RUN_ARGS[@]} | xargs start-stop-daemon \
       --start $CH_USER \
       --pidfile /dev/null \
       --chdir "$JETTY_BASE" \
       --background \
       --output "${JETTY_RUN}/start-stop.log" \
       --startas "$JAVA" \
       --
      (( DEBUG )) && echo "Starting: start-stop-daemon"
    else
      # Startup if switching users (not as a service, or from root)
      if [ -n "$JETTY_USER" ] && [ `whoami` != "$JETTY_USER" ]
      then
        unset SU_SHELL
        if [ "$JETTY_SHELL" ]
        then
          SU_SHELL="-s $JETTY_SHELL"
        fi

        chown "$JETTY_USER" "$JETTY_PID"
        su - "$JETTY_USER" $SU_SHELL -c "
          cd \"$JETTY_BASE\"
          echo ${RUN_ARGS[*]} | xargs ${JAVA} > /dev/null &
          PID=\$!
          disown \$PID"
        (( DEBUG )) && echo "Starting: su shell (w/user $JETTY_USER) on PID $PID"
      else
        # Startup if not switching users
        echo ${RUN_ARGS[*]} | xargs ${JAVA} > /dev/null &
        PID=$!
        disown $PID
        (( DEBUG )) && echo "Starting: java command on PID $PID"
      fi
    fi

    if expr -- "${JETTY_ARGS[*]}" : '.*jetty\.state=.*' >/dev/null
    then
      if started "$JETTY_STATE" "$JETTY_PID" "$JETTY_START_TIMEOUT"
      then
        echo "OK `date`"
      else
        echo "FAILED `date`"
        pidKill $JETTY_PID 30
        exit 1
      fi
    else
      echo "ok `date`"
    fi

    ;;

  stop)
    echo -n "Stopping Jetty: "
    if [ ! -r "$JETTY_PID" ] ; then
      echo "** ERROR: no pid found at $JETTY_PID"
      exit 1
    fi

    PID=$(tail -1 "$JETTY_PID")
    if [ -z "$PID" ] ; then
      echo "** ERROR: no pid found in $JETTY_PID"
      exit 1
    fi

    # Stopping service started with start-stop-daemon
    if (( USE_START_STOP_DAEMON )) ; then
      (( DEBUG )) && echo "Issuing HUP to $PID"
      start-stop-daemon --stop \
         --pid "$PID" \
         --chdir "$JETTY_BASE" \
         --startas "$JAVA" \
         --signal HUP

      TIMEOUT=30
      while running "$JETTY_PID"; do
        (( DEBUG )) && echo "Issuing KILL to $PID"
        if (( TIMEOUT-- == 0 )); then
          start-stop-daemon --stop \
            --pid "$PID" \
            --chdir "$JETTY_BASE" \
            --startas "$JAVA" \
            --signal KILL
        fi

        sleep 1
      done
    else
      # Stopping from non-service start
      pidKill "$JETTY_PID" 30
    fi

    rm -f "$JETTY_PID"
    rm -f "$JETTY_STATE"
    echo OK

    ;;

  restart)
    JETTY_SH=$0
    echo "restart" >> "$JETTY_STATE"
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
    echo ${RUN_ARGS[*]} | xargs ${JAVA} > /dev/null &

    ;;

  run|demo)
    echo "Running Jetty: "

    if running "$JETTY_PID"
    then
      echo Already Running $(cat "$JETTY_PID")!
      exit 1
    fi

    echo ${RUN_ARGS[*]} | xargs ${JAVA} > /dev/null &
    ;;

  check|status)
    if running "$JETTY_PID"
    then
      echo "Jetty running pid=$(< "$JETTY_PID")"
    else
      echo "Jetty NOT running"
    fi
    echo
    dumpEnv
    echo

    if running "$JETTY_PID"
    then
      exit 0
    fi
    exit 1

    ;;

  *)
    usage

    ;;
esac

exit 0
