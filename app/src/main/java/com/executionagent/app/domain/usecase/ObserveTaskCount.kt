package com.executionagent.app.domain.usecase

import com.executionagent.app.domain.repository.TaskRepository
import javax.inject.Inject

class ObserveTaskCount @Inject constructor(private val repository: TaskRepository) {
    operator fun invoke() = repository.observeCount()
}
