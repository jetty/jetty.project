// ========================================================================
// Copyright 2002-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

#include <jni.h>
#include "org_mortbay_setuid_SetUID.h"
#include <sys/types.h>
#include <unistd.h>
#include <sys/resource.h>
#include <stdio.h>
#include <pwd.h>
#include <grp.h>
#include <stdio.h>
#include <errno.h>

/**
 * Native code for SetUID, class is for changing user and groupId, it can also be use to retrieve user information by using getpwuid(uid) or getpwnam(username) of both linux and unix systems
 */


/*  Start of Helper functions Declaration */
jmethodID getJavaMethodId(JNIEnv *env, jclass clazz, const char *name, const char *sig);
void setJavaFieldInt(JNIEnv *env, jobject obj, const char *name, int value);
void setJavaFieldLong(JNIEnv *env, jobject obj, const char *name, long value);
void setJavaFieldString(JNIEnv *env, jobject obj, const char *name, const char *value);
void throwNewJavaException(JNIEnv *env, const char *name, const char *msg);
void throwNewJavaSecurityException(JNIEnv *env, const char *msg);
int getJavaFieldInt(JNIEnv *env, jobject obj, const char *name);
/* End of Helper functions Declaration */

  
JNIEXPORT jint JNICALL 
Java_org_mortbay_setuid_SetUID_setuid (JNIEnv * env, jclass j, jint uid)
{
    return((jint)setuid((uid_t)uid));
}

JNIEXPORT jint JNICALL 
Java_org_mortbay_setuid_SetUID_setumask (JNIEnv * env, jclass j, jint mask)
{
    return((jint)umask((mode_t)mask));
}
  
JNIEXPORT jint JNICALL 
Java_org_mortbay_setuid_SetUID_setgid (JNIEnv * env, jclass j, jint gid)
{
    return((jint)setgid((gid_t)gid));
}



/* User informaton implementatons */

JNIEXPORT jobject JNICALL 
Java_org_mortbay_setuid_SetUID_getpwnam(JNIEnv * env, jclass j, jstring name)
{
    struct passwd* pw;
    jboolean iscopy;
    char *pname; 
    pname = (char*) (*env)->GetStringUTFChars(env, name, &iscopy);
    
    pw=getpwnam((char *) pname);
    if (!pw) 
    {
        char strErr[255];
        sprintf(strErr, "User %s is not found!!!", pname);
        throwNewJavaSecurityException(env, strErr);
        return NULL;
    }
    
    // free as in amnesty
    (*env)->ReleaseStringUTFChars( env, name, pname ); 
    

    // get The java class org.mortbay.setuid.Passwd
    jclass cls;
    cls = (*env)->FindClass(env,"org/mortbay/setuid/Passwd");
    if(!cls)
    {
        throwNewJavaSecurityException(env, "Class: org.mortbay.setuid.Passwd is not found!!!");
        return NULL;
    }
    
    // get the default constructor  of org.mortbay.setuid.Passwd
    jmethodID constructorMethod = getJavaMethodId(env, cls, "<init>", "()V");
    
    // construct org.mortbay.setuid.Passwd java object
    jobject retVal = (*env)->NewObject(env, cls,constructorMethod);
    if(!retVal)
    {
        throwNewJavaSecurityException(env, "Object Construction error of Class: org.mortbay.setuid.Passwd!!!");
        return NULL;
    }
    
    
    // copy the struct passwd values to java object org.mortbay.setuid.Passwd
    //char *pw_name;
    setJavaFieldString(env, retVal, "_pwName", pw->pw_name);
	//char *pw_passwd;
    setJavaFieldString(env, retVal, "_pwPasswd", pw->pw_passwd);
	//uid_t pw_uid;
    setJavaFieldInt(env, retVal, "_pwUid", pw->pw_uid);   
	//gid_t pw_gid;
    setJavaFieldInt(env, retVal, "_pwGid", pw->pw_gid);
	//char *pw_gecos;
    setJavaFieldString(env, retVal, "_pwGecos", pw->pw_gecos);
	//char *pw_dir;
    setJavaFieldString(env, retVal, "_pwDir", pw->pw_dir);
	//char *pw_shell;
    setJavaFieldString(env, retVal, "_pwShell", pw->pw_shell);
	
    (*env)->DeleteLocalRef(env, cls);
    return retVal;


}


JNIEXPORT jobject JNICALL 
Java_org_mortbay_setuid_SetUID_getpwuid(JNIEnv * env, jclass j, jint uid)
{
    struct passwd* pw;
    pw=getpwuid((uid_t) uid);
    if (!pw) 
    {
        char strErr[255];
        sprintf(strErr, "User with uid %d is not found!!!", uid);
        throwNewJavaSecurityException(env, strErr);
        return NULL;
    }
    

    // get The java class org.mortbay.setuid.Passwd
    
    jclass cls;
    cls = (*env)->FindClass(env,"org/mortbay/setuid/Passwd");
    if(!cls)
    {
        throwNewJavaSecurityException(env, "Class: org.mortbay.setuid.Passwd is not found!!!");
        return NULL;
    }
    
    // get the default constructor  of org.mortbay.setuid.Passwd
    jmethodID constructorMethod = getJavaMethodId(env, cls, "<init>", "()V");
    
    // construct org.mortbay.setuid.Passwd java object
    jobject retVal = (*env)->NewObject(env, cls,constructorMethod);
    if(!retVal)
    {
        throwNewJavaSecurityException(env, "Object Construction error of Class: org.mortbay.setuid.Passwd!!!");
        return NULL;
    }
    
    
    // copy the struct passwd values to java object org.mortbay.setuid.Passwd
    //char *pw_name;
    setJavaFieldString(env, retVal, "_pwName", pw->pw_name);
	//char *pw_passwd;
    setJavaFieldString(env, retVal, "_pwPasswd", pw->pw_passwd);
	//uid_t pw_uid;
    setJavaFieldInt(env, retVal, "_pwUid", pw->pw_uid);   
	//gid_t pw_gid;
    setJavaFieldInt(env, retVal, "_pwGid", pw->pw_gid);
	//char *pw_gecos;
    setJavaFieldString(env, retVal, "_pwGecos", pw->pw_gecos);
	//char *pw_dir;
    setJavaFieldString(env, retVal, "_pwDir", pw->pw_dir);
	//char *pw_shell;
    setJavaFieldString(env, retVal, "_pwShell", pw->pw_shell);
	
    (*env)->DeleteLocalRef(env, cls);
    return retVal;
}




/*  Group information implimentations */

JNIEXPORT jobject JNICALL 
Java_org_mortbay_setuid_SetUID_getgrnam(JNIEnv * env, jclass j, jstring name)
{
    struct group* gr;
    jboolean iscopy;
    char *pname; 
    pname = (char*) (*env)->GetStringUTFChars(env, name, &iscopy);
    
    gr=getgrnam((char *) pname);
    if (!gr) 
    {
        char strErr[255];
        sprintf(strErr, "Group %s is not found!!!", pname);
        throwNewJavaSecurityException(env, strErr);
        return NULL;
    }
    

    // free as in amnesty
    (*env)->ReleaseStringUTFChars( env, name, pname ); 
    

    // get The java class org.mortbay.setuid.Passwd
    jclass cls;
    cls = (*env)->FindClass(env,"org/mortbay/setuid/Group");
    if(!cls)
    {
        throwNewJavaSecurityException(env, "Class: org.mortbay.setuid.Group is not found!!!");
        return NULL;
    }
    
    // get the default constructor  of org.mortbay.setuid.Group
    jmethodID constructorMethod = getJavaMethodId(env, cls, "<init>", "()V");
    
    // construct org.mortbay.setuid.Group java object
    jobject retVal = (*env)->NewObject(env, cls,constructorMethod);
    if(!retVal)
    {
        throwNewJavaSecurityException(env, "Object Construction error of Class: org.mortbay.setuid.Group!!!");
        return NULL;
    }
    
    // copy the struct grpup values to java object org.mortbay.setuid.Group
    //char *gr_name;
    setJavaFieldString(env, retVal, "_grName", gr->gr_name);
	//char *gr_passwd;
    setJavaFieldString(env, retVal, "_grPasswd", gr->gr_passwd);
	//gid_t   gr_gid;
    setJavaFieldInt(env, retVal, "_grGid", gr->gr_gid);
     
    if (gr->gr_mem != NULL) 
    {
        int array_size, i;
        jobjectArray gr_mems;
        
        for(array_size =0; gr->gr_mem[array_size] != NULL; array_size++); 
        
        if(array_size)
        {
            jobjectArray strArr =  (*env)->NewObjectArray(env, array_size, 
                                                          (*env)->FindClass(env, "java/lang/String"), 
                                                          (*env)->NewStringUTF(env, ""));
            
            for(i=0;i<array_size;i++) 
            {
                (*env)->SetObjectArrayElement(env,strArr,i,
                                              (*env)->NewStringUTF(env, gr->gr_mem[i]));
            }
            
            
            // set string array field;
            // find field
            jfieldID fieldId =  (*env)->GetFieldID(env, cls, "_grMem", "[Ljava/lang/String;");
            if(!fieldId)
            {
                throwNewJavaSecurityException(env, "Class: Java Object Field is not found: String[] _grMem!!!");
            }
            
            (*env)->SetObjectField(env, retVal, fieldId, strArr); 
        }  
    }
	
    (*env)->DeleteLocalRef(env, cls);
    return retVal;
}

JNIEXPORT jobject JNICALL 
Java_org_mortbay_setuid_SetUID_getgrgid(JNIEnv * env, jclass j, jint gid)
{
    struct group* gr;
    
    gr=getgrgid(gid);
    if (!gr) 
    {
        char strErr[255];
        sprintf(strErr, "Group with gid %d is not found!!!", gid);
        throwNewJavaSecurityException(env, strErr);
        return NULL;
    }
    

    // get The java class org.mortbay.setuid.Passwd
    jclass cls;
    cls = (*env)->FindClass(env,"org/mortbay/setuid/Group");
    if(!cls)
    {
        throwNewJavaSecurityException(env, "Class: org.mortbay.setuid.Group is not found!!!");
        return NULL;
    }
    
    // get the default constructor  of org.mortbay.setuid.Group
    jmethodID constructorMethod = getJavaMethodId(env, cls, "<init>", "()V");
    
    // construct org.mortbay.setuid.Group java object
    jobject retVal = (*env)->NewObject(env, cls,constructorMethod);
    if(!retVal)
    {
        throwNewJavaSecurityException(env, "Object Construction Error of Class: org.mortbay.setuid.Group!!!");
        return NULL;
    }
    
    
    
    // copy the struct grpup values to java object org.mortbay.setuid.Group
    //char *gr_name;
    setJavaFieldString(env, retVal, "_grName", gr->gr_name);
	//char *gr_passwd;
    setJavaFieldString(env, retVal, "_grPasswd", gr->gr_passwd);
	//gid_t   gr_gid;
    setJavaFieldInt(env, retVal, "_grGid", gr->gr_gid);
	
    
    
    
    if (gr->gr_mem != NULL) 
    {
        int array_size, i;
        jobjectArray gr_mems;
        
        for(array_size =0; gr->gr_mem[array_size] != NULL; array_size++); 
        
        if(array_size)
        {
            jobjectArray strArr =  (*env)->NewObjectArray(env, array_size, 
                                                          (*env)->FindClass(env, "java/lang/String"), 
                                                          (*env)->NewStringUTF(env, ""));
            
            for(i=0;i<array_size;i++) 
            {
                (*env)->SetObjectArrayElement(env,strArr,i,
                                              (*env)->NewStringUTF(env, gr->gr_mem[i]));
            }

            // set string array field;
            // find field
            jfieldID fieldId =  (*env)->GetFieldID(env, cls, "_grMem", "[Ljava/lang/String;");
            if(!fieldId)
            {
                throwNewJavaSecurityException(env, "Java Object Field is not found: String _grMem!!!");
            }
            
            (*env)->SetObjectField(env, retVal, fieldId, strArr); 
        }
    }
    
    (*env)->DeleteLocalRef(env, cls);
    return retVal;
}


/*
 * Class:     org_mortbay_setuid_SetUID
 * Method:    getrlimitnofiles
 * Signature: ()Lorg/mortbay/setuid/RLimit;
 */
JNIEXPORT jobject JNICALL Java_org_mortbay_setuid_SetUID_getrlimitnofiles
  (JNIEnv *env, jclass j)
{
    struct rlimit rlim;
    int success = getrlimit(RLIMIT_NOFILE, &rlim);
    if (success < 0)
    {
        throwNewJavaSecurityException(env, "getrlimit failed");
        return NULL;
    }

    // get The java class org.mortbay.setuid.RLimit
    jclass cls = (*env)->FindClass(env, "org/mortbay/setuid/RLimit");
    if(!cls)
    {
        throwNewJavaSecurityException(env, "Class: org.mortbay.setuid.RLimit is not found!!!");
        return NULL;
    }
    
    // get the default constructor  of org.mortbay.setuid.RLimit
    jmethodID constructorMethod = getJavaMethodId(env, cls, "<init>", "()V");
    
    // construct org.mortbay.setuid.RLimit java object
    jobject retVal = (*env)->NewObject(env, cls,constructorMethod);
    if(!retVal)
    {
        throwNewJavaSecurityException(env, "Object Construction Error of Class: org.mortbay.setuid.RLimit!!!");
        return NULL;
    }
    setJavaFieldInt(env, retVal, "_soft", rlim.rlim_cur);
    setJavaFieldInt(env, retVal, "_hard", rlim.rlim_max);

    (*env)->DeleteLocalRef(env, cls);
    return retVal;
}

/*
 * Class:     org_mortbay_setuid_SetUID
 * Method:    setrlimitnofiles
 * Signature: (Lorg/mortbay/setuid/RLimit;)I
 */
JNIEXPORT jint JNICALL Java_org_mortbay_setuid_SetUID_setrlimitnofiles
  (JNIEnv *env, jclass j, jobject jo)
{
    struct rlimit rlim;

    jclass cls = (*env)->FindClass(env, "org/mortbay/setuid/RLimit");
    rlim.rlim_cur=getJavaFieldInt(env,jo, "_soft");
    rlim.rlim_max=getJavaFieldInt(env,jo, "_hard");
    int success = setrlimit(RLIMIT_NOFILE, &rlim);
    (*env)->DeleteLocalRef(env, cls);
    return (jint)success;  
}





/*  Start of Helper Functions Implimentations */

jmethodID getJavaMethodId(JNIEnv *env, jclass clazz, const char *name, const char *sig)
{
    jmethodID methodId = (*env)->GetMethodID(env, clazz,name,sig);
    if(!methodId)
    {
        char strErr[255];
        sprintf(strErr, "Java Method is not found: %s !!!", name);
        throwNewJavaSecurityException(env, strErr);
        return NULL;
    }
    
    return methodId;

}

int getJavaFieldInt(JNIEnv *env, jobject obj, const char *name)
{
    jclass clazz = (*env)->GetObjectClass(env, obj);
    jfieldID fieldId =  (*env)->GetFieldID(env, clazz, name, "I");
    if(!fieldId)
    {
        char strErr[255];
        sprintf(strErr, "Java Object Field is not found: int %s !!!", name);
        throwNewJavaSecurityException(env, strErr);
    }
    int val = (*env)->GetIntField(env, obj, fieldId);
    (*env)->DeleteLocalRef(env, clazz);
    return val;
}

void setJavaFieldInt(JNIEnv *env, jobject obj, const char *name, int value)
{
    jclass clazz = (*env)->GetObjectClass(env, obj);

    jfieldID fieldId =  (*env)->GetFieldID(env, clazz, name, "I");
    if(!fieldId)
    {
        char strErr[255];
        sprintf(strErr, "Java Object Field is not found: int %s !!!", name);
        throwNewJavaSecurityException(env, strErr);
    }
    
    (*env)->SetIntField(env, obj, fieldId, value);
    (*env)->DeleteLocalRef(env, clazz);
}


void setJavaFieldLong(JNIEnv *env, jobject obj, const char *name, long value)
{
    jclass clazz = (*env)->GetObjectClass(env, obj);

    jfieldID fieldId =  (*env)->GetFieldID(env, clazz, name, "L");
    if(!fieldId)
    {
        char strErr[255];
        sprintf(strErr, "Java Object Field is not found: long %s !!!", name);
        throwNewJavaSecurityException(env, strErr);
    }
    
    (*env)->SetLongField(env, obj, fieldId, value);
    (*env)->DeleteLocalRef(env, clazz);
    
}


void setJavaFieldString(JNIEnv *env, jobject obj, const char *name, const char *value)
{
    jclass clazz = (*env)->GetObjectClass(env, obj);

    jfieldID fieldId =  (*env)->GetFieldID(env, clazz, name, "Ljava/lang/String;");
    if(!fieldId)
    {
        char strErr[255];
        sprintf(strErr, "Java Object Field is not found: String %s !!!", name);
        throwNewJavaSecurityException(env, strErr);
    }
    
    jstring jstr = (*env)->NewStringUTF(env, value);
    
    
    (*env)->SetObjectField(env, obj, fieldId, jstr);
    (*env)->DeleteLocalRef(env, clazz);
}



void throwNewJavaException(JNIEnv *env, const char *name, const char *msg)
{
    jclass clazz = (*env)->FindClass(env, name);
    if (clazz) 
    {
        (*env)->ThrowNew(env, clazz, msg);
    }
    (*env)->DeleteLocalRef(env, clazz);
}

void throwNewJavaSecurityException(JNIEnv *env, const char *msg)
{
    throwNewJavaException(env, "java/lang/SecurityException", msg);
}



/*  End of Helper Functions Implimentations */

