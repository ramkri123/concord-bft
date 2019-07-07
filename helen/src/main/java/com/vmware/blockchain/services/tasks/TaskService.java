/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.tasks;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.vmware.blockchain.dao.GenericDao;

/**
 * Perform any businees logic for a task.  At this point, there isn't much.
 * Interface for task service pulled out for use in testing.
 */
@Service
public class TaskService implements ITaskService {

    private GenericDao genericDao;

    @Autowired
    public TaskService(GenericDao genericDao) {
        this.genericDao = genericDao;
    }

    @Override
    public Task get(UUID taskId) {
        return genericDao.getEntityByTenant(taskId, Task.class);
    }

    @Override
    public Task put(Task task) {
        return genericDao.putUnderTenant(task, null);
    }

    @Override
    public Task merge(Task task, Consumer<Task> merger) {
        return genericDao.mergeWithRetry(task, Task.class, merger);
    }

    @Override
    public List<Task> list() {
        return genericDao.getAllByType(Task.class);
    }
}
