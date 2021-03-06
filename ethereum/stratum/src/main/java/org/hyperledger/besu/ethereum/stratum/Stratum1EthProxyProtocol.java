/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.stratum;

import static org.apache.logging.log4j.LogManager.getLogger;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.mainnet.DirectAcyclicGraphSeed;
import org.hyperledger.besu.ethereum.mainnet.EthHashSolution;
import org.hyperledger.besu.ethereum.mainnet.EthHashSolverInputs;
import org.hyperledger.besu.util.bytes.BytesValue;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.io.BaseEncoding;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.Logger;

/**
 * Implementation of the stratum1+tcp protocol.
 *
 * <p>This protocol allows miners to submit EthHash solutions over a persistent TCP connection.
 */
public class Stratum1EthProxyProtocol implements StratumProtocol {
  private static final Logger LOG = getLogger();
  private static final JsonMapper mapper = new JsonMapper();

  private EthHashSolverInputs currentInput;
  private Function<EthHashSolution, Boolean> submitCallback;

  @Override
  public boolean canHandle(final String initialMessage, final StratumConnection conn) {
    JsonRpcRequestContext req;
    try {
      req = new JsonObject(initialMessage).mapTo(JsonRpcRequestContext.class);
    } catch (IllegalArgumentException e) {
      LOG.debug(e.getMessage(), e);
      return false;
    }
    if (!"eth_submitLogin".equals(req.getRequest().getMethod())) {
      LOG.debug("Invalid first message method: {}", req.getRequest().getMethod());
      return false;
    }

    try {
      String response =
          mapper.writeValueAsString(new JsonRpcSuccessResponse(req.getRequest().getId(), true));
      conn.send(response + "\n");
    } catch (JsonProcessingException e) {
      LOG.debug(e.getMessage(), e);
      conn.close(null);
    }

    return true;
  }

  private void sendNewWork(final StratumConnection conn, final Object id) {
    byte[] dagSeed = DirectAcyclicGraphSeed.dagSeed(currentInput.getBlockNumber());
    final String[] result = {
      "0x" + BaseEncoding.base16().lowerCase().encode(currentInput.getPrePowHash()),
      "0x" + BaseEncoding.base16().lowerCase().encode(dagSeed),
      currentInput.getTarget().toHexString()
    };
    JsonRpcSuccessResponse req = new JsonRpcSuccessResponse(id, result);
    try {
      conn.send(mapper.writeValueAsString(req) + "\n");
    } catch (JsonProcessingException e) {
      LOG.debug(e.getMessage(), e);
    }
  }

  @Override
  public void onClose(final StratumConnection conn) {}

  @Override
  public void handle(final StratumConnection conn, final String message) {
    try {
      final JsonRpcRequest req = new JsonObject(message).mapTo(JsonRpcRequest.class);
      if ("eth_getWork".equals(req.getMethod())) {
        sendNewWork(conn, req.getId());
      } else if ("eth_submitWork".equals(req.getMethod())) {
        handleMiningSubmit(conn, req);
      }
    } catch (IllegalArgumentException | IOException e) {
      LOG.debug(e.getMessage(), e);
      conn.close(null);
    }
  }

  private void handleMiningSubmit(final StratumConnection conn, final JsonRpcRequest req)
      throws IOException {
    LOG.debug("Miner submitted solution {}", req);
    boolean result = false;
    final EthHashSolution solution =
        new EthHashSolution(
            BytesValue.fromHexString(req.getRequiredParameter(0, String.class)).getLong(0),
            req.getRequiredParameter(2, Hash.class),
            BytesValue.fromHexString(req.getRequiredParameter(1, String.class)).getArrayUnsafe());
    if (Arrays.equals(currentInput.getPrePowHash(), solution.getPowHash())) {
      result = submitCallback.apply(solution);
    }

    String response = mapper.writeValueAsString(new JsonRpcSuccessResponse(req.getId(), result));
    conn.send(response + "\n");
  }

  @Override
  public void setCurrentWorkTask(final EthHashSolverInputs input) {
    this.currentInput = input;
  }

  @Override
  public void setSubmitCallback(final Function<EthHashSolution, Boolean> submitSolutionCallback) {
    this.submitCallback = submitSolutionCallback;
  }
}
