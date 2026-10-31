package com.paypalseed.platform.kernel;

public sealed interface Result<T> permits Result.Success, Result.Failure { record Success<T>(T value) implements Result<T> {} record Failure<T>(String error) implements Result<T> {} }
