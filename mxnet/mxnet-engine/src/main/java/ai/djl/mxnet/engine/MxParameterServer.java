/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package ai.djl.mxnet.engine;

import ai.djl.mxnet.jna.JnaUtils;
import ai.djl.mxnet.jna.MxnetLibrary;
import ai.djl.mxnet.jna.NativeResource;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.training.ParameterServer;
import ai.djl.training.optimizer.Optimizer;
import com.sun.jna.Pointer;
import java.util.Arrays;

/** {@code MxParameterServer} is the MXNet implementation of {@link ParameterServer}. */
public class MxParameterServer extends NativeResource implements ParameterServer {

    /**
     * Constructs a new {@code MxParameterServer}.
     *
     * @param optimizer the optimizer to use for the parameter server updates
     */
    public MxParameterServer(Optimizer optimizer) {
        super(createdKVStore());
        JnaUtils.parameterStoreSetUpdater(
                getHandle(), null, new OptimizerCallback(optimizer), null);
    }

    /** {@inheritDoc} */
    @Override
    public void init(String parameterId, NDArray[] values) {
        String[] keys = new String[values.length];
        Arrays.fill(keys, parameterId);
        NDList vals = new NDList(values);
        JnaUtils.parameterStoreInit(getHandle(), values.length, keys, vals);
    }

    /** {@inheritDoc} */
    @Override
    public void push(String parameterId, NDArray[] grads, int priority) {
        String[] keys = new String[grads.length];
        Arrays.fill(keys, parameterId);
        NDList vals = new NDList(grads);
        JnaUtils.parameterStorePush(getHandle(), grads.length, keys, vals, priority);
    }

    /** {@inheritDoc} */
    @Override
    public void pull(String parameterId, NDArray[] weights, int priority) {
        String[] keys = new String[weights.length];
        Arrays.fill(keys, parameterId);
        NDList vals = new NDList(weights);
        JnaUtils.parameterStorePull(getHandle(), weights.length, keys, vals, priority);
    }

    /**
     * Pushes the value of a key from Parameter Server to NDArrays and Pulls right away.
     *
     * @param parameterId the key to pull
     * @param inputs the NDArrays to store the value corresponding to the key, value will be copied
     *     to the devices of the NDArrays
     * @param outputs the NDArrays to get the value corresponding to the key, value will be copied
     *     to the devices of the NDArrays
     * @param priority the priority of the push operation. Higher priority push operations are
     *     likely to be executed before other push actions
     */
    public void pushPull(String parameterId, NDArray[] inputs, NDArray[] outputs, int priority) {
        String[] gradKeys = new String[inputs.length];
        String[] weightKeys = new String[outputs.length];
        Arrays.fill(gradKeys, parameterId);
        Arrays.fill(weightKeys, parameterId);
        JnaUtils.parameterStorePushPull(
                getHandle(),
                inputs.length,
                gradKeys,
                outputs.length,
                weightKeys,
                new NDList(inputs),
                new NDList(outputs),
                priority);
    }

    private static Pointer createdKVStore() {
        return JnaUtils.parameterStoreCreate("device");
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        Pointer pointer = handle.getAndSet(null);
        if (pointer != null) {
            JnaUtils.parameterStoreClose(pointer);
        }
    }

    /** A helper to wrap the optimizer so it can be called by the MXNet KVStore. */
    private static final class OptimizerCallback implements MxnetLibrary.MXKVStoreStrUpdater {

        private Optimizer optimizer;

        OptimizerCallback(Optimizer optimizer) {
            this.optimizer = optimizer;
        }

        /** {@inheritDoc} */
        @Override
        public void apply(String parameterId, Pointer recv, Pointer local, Pointer handle) {
            // updater callback arguments order is: index, gradient, weight.
            try (MxNDManager manager = MxNDManager.getSystemManager().newSubManager()) {
                MxNDArray grad = manager.create(recv);
                MxNDArray weight = manager.create(local);
                grad.setShouldFree(false);
                weight.setShouldFree(false);
                optimizer.update(parameterId, weight, grad);
            }
        }
    }
}
