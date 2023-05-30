// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include "liveupdate.h"
#include "liveupdate_private.h"

#include <dlib/array.h>
#include <dlib/atomic.h>
#include <dlib/condition_variable.h>
#include <dlib/mutex.h>
#include <dlib/thread.h>
#include <resource/resource.h>
#include <resource/resource_archive.h>

#include <dlib/atomic.h>
#include <dlib/thread.h>
#include <dlib/mutex.h>
#include <dlib/condition_variable.h>
#include <dlib/array.h>
#include <dmsdk/dlib/profile.h>

namespace dmLiveUpdate
{
    /// Resource system factory, used for async locking of load mutex
    static dmResource::HFactory m_ResourceFactory = 0x0;
    /// How many elements to offset queue capacity with when full
    static const uint32_t m_JobQueueSizeIncrement = 32;

    /// The liveupdate thread and synchronization objects, used for sequentially processing async liveupdate resource requests
    static dmThread::Thread m_AsyncThread = 0x0;
    static dmMutex::HMutex  m_ConsumerThreadMutex;
    static dmConditionVariable::HConditionVariable m_ConsumerThreadCondition;
    static int32_atomic_t m_ThreadJobComplete = 0;
    static int32_atomic_t m_Active = 0;

    /// job input and output queues
    static dmArray<AsyncResourceRequest> m_JobQueue;
    static dmArray<AsyncResourceRequest> m_ThreadJobQueue;
    static AsyncResourceRequest m_TreadJob;
    static ResourceRequestCallbackData m_JobCompleteData;


    static void ProcessRequest(AsyncResourceRequest &request)
    {
        m_JobCompleteData.m_CallbackData = request.m_CallbackData;
        m_JobCompleteData.m_Callback = request.m_Callback;
        m_JobCompleteData.m_Status = false;
        Result res = dmLiveUpdate::RESULT_OK;
        if (request.m_IsArchive)
        {
            // Stores/stages a zip archive for loading after next reboot
            res = dmLiveUpdate::StoreZipArchive(request.m_Path, request.m_VerifyArchive);
            m_JobCompleteData.m_Manifest = 0;
        }
        else if (request.m_Resource.m_Header != 0x0)
        {
            // Add a resource to the currently created live update archive
            res = dmLiveUpdate::NewArchiveIndexWithResource(request.m_Manifest, request.m_ExpectedResourceDigest, request.m_ExpectedResourceDigestLength, &request.m_Resource, m_JobCompleteData.m_NewArchiveIndex);
            m_JobCompleteData.m_Manifest = request.m_Manifest;
        }
        else
        {
            res = dmLiveUpdate::RESULT_INVALID_HEADER;
        }
        m_JobCompleteData.m_Status = res == dmLiveUpdate::RESULT_OK ? true : false;
    }

    // Must be called on the Lua main thread
    static void ProcessRequestComplete()
    {
        if(m_JobCompleteData.m_Manifest && m_JobCompleteData.m_Status)
        {
            // If we have a new archive, then we've also created a new manifest, so let's use it
            dmLiveUpdate::SetNewManifest(m_JobCompleteData.m_Manifest);

            dmLiveUpdate::SetNewArchiveIndex(m_JobCompleteData.m_Manifest->m_ArchiveIndex, m_JobCompleteData.m_NewArchiveIndex, true);
        }
        m_JobCompleteData.m_Callback(m_JobCompleteData.m_Status, m_JobCompleteData.m_CallbackData);
    }


#if !(defined(__EMSCRIPTEN__))
    static void AsyncThread(void* args)
    {
        (void)args;

        // Liveupdate async thread batch processing requested liveupdate tasks
        AsyncResourceRequest request;
        while (dmAtomicGet32(&m_Active))
        {
            DM_PROFILE("Update");
            // Lock and sleep until signaled there is requests queued up
            {
                dmMutex::ScopedLock lk(m_ConsumerThreadMutex);
                while(m_ThreadJobQueue.Empty())
                    dmConditionVariable::Wait(m_ConsumerThreadCondition, m_ConsumerThreadMutex);
                if(dmAtomicGet32(&m_ThreadJobComplete) || !dmAtomicGet32(&m_Active))
                    continue;
                request = m_ThreadJobQueue.Back();
                m_ThreadJobQueue.Pop();
            }
            ProcessRequest(request);
            dmAtomicStore32(&m_ThreadJobComplete, 1);
        }
    }

    void AsyncUpdate()
    {
        if(dmAtomicGet32(&m_Active) && (dmAtomicGet32(&m_ThreadJobComplete) || (!m_JobQueue.Empty())))
        {
            // Process any completed jobs, lock resource loadmutex as we will swap (update) archive containers archiveindex data
            dmMutex::ScopedLock lk(m_ConsumerThreadMutex);
            if(dmAtomicGet32(&m_ThreadJobComplete))
            {
                dmMutex::HMutex mutex = dmResource::GetLoadMutex(m_ResourceFactory);
                if(!dmMutex::TryLock(mutex))
                    return;
                ProcessRequestComplete();
                dmMutex::Unlock(mutex);
                dmAtomicStore32(&m_ThreadJobComplete, 0);
            }
            // Push any accumulated request queue batch to job queue
            if(!m_JobQueue.Empty())
            {
                if(m_ThreadJobQueue.Remaining() < m_JobQueue.Size())
                {
                    m_ThreadJobQueue.SetCapacity(m_ThreadJobQueue.Size() + m_JobQueue.Size() + m_JobQueueSizeIncrement);
                }
                m_ThreadJobQueue.PushArray(m_JobQueue.Begin(), m_JobQueue.Size());
                m_JobQueue.SetSize(0);
            }
            // Either conditions should signal worker thread to check for new jobs
            dmConditionVariable::Signal(m_ConsumerThreadCondition);
        }
    }

    bool AddAsyncResourceRequest(AsyncResourceRequest& request)
    {
        // Add single job to job queue that will be batch pushed to thread worker in AsyncUpdate
        if(!dmAtomicGet32(&m_Active))
            return false;
        if(m_JobQueue.Full())
        {
            m_JobQueue.OffsetCapacity(m_JobQueueSizeIncrement);
        }
        m_JobQueue.Push(request);
        return true;
    }

    void AsyncInitialize(const dmResource::HFactory factory)
    {
        m_ResourceFactory = factory;
        m_JobQueue.SetCapacity(m_JobQueueSizeIncrement);
        m_JobQueue.SetSize(0);
        m_ThreadJobQueue.SetCapacity(m_JobQueueSizeIncrement);
        m_ThreadJobQueue.SetSize(0);
        m_ConsumerThreadMutex = dmMutex::New();
        m_ConsumerThreadCondition = dmConditionVariable::New();
        dmAtomicStore32(&m_ThreadJobComplete, 0);
        dmAtomicStore32(&m_Active, 1);
        m_AsyncThread = dmThread::New(AsyncThread, 0x80000, 0, "liveupdate");
    }

    void AsyncFinalize()
    {
        if(m_AsyncThread)
        {
            // When shutting down, discard any complete jobs as they are going to be mounted at boot time in any case
            dmAtomicStore32(&m_Active, 0);

            {
                DM_MUTEX_SCOPED_LOCK(m_ConsumerThreadMutex);
                m_JobQueue.SetSize(0);
                m_ThreadJobQueue.SetSize(1);
                dmConditionVariable::Signal(m_ConsumerThreadCondition);
            }
            dmThread::Join(m_AsyncThread);
            dmConditionVariable::Delete(m_ConsumerThreadCondition);
            dmMutex::Delete(m_ConsumerThreadMutex);
            m_AsyncThread = 0;
        }
    }

#else

    void AsyncUpdate()
    {
        if(!m_JobQueue.Empty())
        {
            ProcessRequest(m_JobQueue.Back());
            m_JobQueue.Pop();
            ProcessRequestComplete();
        }
    }

    bool AddAsyncResourceRequest(AsyncResourceRequest& request)
    {
        // Add single job to job queue that will be batch pushed to thread worker in AsyncUpdate
        if(!dmAtomicGet32(&m_Active))
            return false;
        if(m_JobQueue.Full())
        {
            m_JobQueue.OffsetCapacity(m_JobQueueSizeIncrement);
        }
        m_JobQueue.Push(request);
        return true;
    }

    void AsyncInitialize(const dmResource::HFactory factory)
    {
        m_ResourceFactory = factory;
        dmAtomicStore32(&m_Active, 1);
    }

    void AsyncFinalize()
    {
        dmAtomicStore32(&m_Active, 0);
        m_JobQueue.SetSize(0);
    }

#endif

};
