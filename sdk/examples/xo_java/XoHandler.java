/* Copyright 2017 Intel Corporation
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
------------------------------------------------------------------------------*/

package sawtooth.examples.xo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.apache.commons.lang3.StringUtils;

import sawtooth.sdk.processor.State;
import sawtooth.sdk.processor.TransactionHandler;
import sawtooth.sdk.processor.Utils;
import sawtooth.sdk.processor.exceptions.InternalError;
import sawtooth.sdk.processor.exceptions.InvalidTransactionException;
import sawtooth.sdk.protobuf.TpProcessRequest;
import sawtooth.sdk.protobuf.TransactionHeader;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class XoHandler implements TransactionHandler {

  private final Logger logger = Logger.getLogger(XoHandler.class.getName());
  private String xoNameSpace;

  /**
   * constructor.
   */
  public XoHandler() {
    try {
      this.xoNameSpace = Utils.hash512(
        this.transactionFamilyName().getBytes("UTF-8")).substring(0, 6);
    } catch (UnsupportedEncodingException usee) {
      usee.printStackTrace();
      this.xoNameSpace = "";
    }
  }

  @Override
  public String transactionFamilyName() {
    return "xo";
  }

  @Override
  public String getEncoding() {
    return "csv-utf8";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public Collection<String> getNameSpaces() {
    ArrayList<String> namespaces = new ArrayList<>();
    namespaces.add(this.xoNameSpace);
    return namespaces;
  }

  @Override
  public void apply(TpProcessRequest transactionRequest, State stateStore)
      throws InvalidTransactionException, InternalError {
    String[] transactionData = getUnpackedTransaction(transactionRequest);
    String action = transactionData[0];
    String gameName = transactionData[1];
    String space = transactionData[2];

    if (gameName.equals("")) {
      throw new InvalidTransactionException("Name is required");
    }

    if (action.equals("")) {
      throw new InvalidTransactionException("Action is required");
    }

    String[] stateData = getStateData(gameName, stateStore);
    String board = stateData[0];
    String state = stateData[1];
    String player1 = stateData[2];
    String player2 = stateData[3];

    String signer = getSignerPubkey(transactionRequest);
    String[] updatedGameData = playXo(
      gameName, board, state,
      player1, player2,
      signer, action, space
    );

    storeGameData(gameName, updatedGameData, stateStore);
  }

  /**
   * Helper function to get signer public key from transaction header.
   */
  private String getSignerPubkey(TpProcessRequest transactionRequest) throws InternalError {
    try {
      TransactionHeader header = TransactionHeader.parseFrom(transactionRequest.getHeader());
      return header.getSignerPubkey();
    } catch (InvalidProtocolBufferException e) {
      throw new InternalError("Protocol Buffer Error: " + e.toString());
    }
  }

  /**
   * Helper function to retrieve game name, action, and space from transaction request.
   */
  private String[] getUnpackedTransaction(TpProcessRequest transactionRequest) {
    String[] transactionData = {"", "", ""};
    String[] payload =  csvDecodeData(transactionRequest.getPayload().toStringUtf8());
    System.arraycopy(payload, 0, transactionData, 0, payload.length);
    return transactionData;
  }

  /**
   * Helper function to retrieve the board, state, player1, and player2 from state store.
   */
  private String[] getStateData(String gameName, State stateStore)
      throws InternalError, InvalidTransactionException {
    String[] stateData = {"", "", "", ""};
    String address = makeGameAddress(gameName);

    String stateEntry = getJsonStringByAddress(address, stateStore);
    System.out.println("StateEntry is: " + stateEntry);
    if (stateEntry.length() != 0) {
      Map<String, String> mapNamesToCsv = getMapNamesToCsv(stateEntry);
      String dataCsv = mapNamesToCsv.get(gameName);
      String[] decodedStateData = csvDecodeData(dataCsv);
      System.arraycopy(decodedStateData, 0, stateData, 0, decodedStateData.length);
    }
    return stateData;
  }

  /**
   * Helper function to retrieve a JSON string containing the state data for a given address from
   * the state store.
   */
  private String getJsonStringByAddress(String address, State stateStore)
      throws InternalError, InvalidTransactionException {
    Map<String, ByteString>  stateEntries = stateStore.get(Collections.singletonList(address));
    return stateEntries.get(address).toStringUtf8();
  }

  /**
   * Helper function to get a map of names to CSV game data from a JSON string.
   */
  private Map<String, String> getMapNamesToCsv(String jsonString) {
    Type type = new TypeToken<Map<String, String>>(){}.getType();
    GsonBuilder builder = new GsonBuilder();
    Gson gson = builder.create();
    Map<String, String> map = gson.fromJson(jsonString, type);
    if (map != null) {
      return map;
    } else {
      return new HashMap<>();
    }
  }

  /**
   * Helper function to store state data.
   */
  private void storeGameData(String gameName, String[] gameData, State stateStore)
      throws InternalError, InvalidTransactionException {
    //TODO: Store as an array rather than map
    String address = makeGameAddress(gameName);
    String gameDataCsv = toCsv(gameData);

    String oldJsonString = getJsonStringByAddress(address, stateStore);
    Map<String, String> mapNamesToCsv = getMapNamesToCsv(oldJsonString);
    mapNamesToCsv.put(gameName, gameDataCsv);
    String newJsonString = convertMapToJson(mapNamesToCsv);
    ByteString jsonBytes = ByteString.copyFromUtf8(newJsonString);

    Map.Entry<String, ByteString> entry = createStateEntry(address, jsonBytes);
    stateStore.set(Collections.singletonList(entry));
  }

  /**
   * Helper function to take an address and ByteString containing CSV of game data and turn it into
   * a ByteString that can be stored in state.
   */
  private Map.Entry<String,ByteString> createStateEntry(String address, ByteString jsonBytes) {
    return new AbstractMap.SimpleEntry<>(
          address,
          jsonBytes
        );
  }

  /**
   * Helper function to convert a map object to json.
   */
  private String convertMapToJson(Map<String, String> mapNamesToCsv) {
    Type type = new TypeToken<Map<String, String>>(){}.getType();
    GsonBuilder builder = new GsonBuilder();
    Gson gson = builder.create();
    return gson.toJson(mapNamesToCsv, type);
  }

  /**
   * Helper function to generate game address.
   */
  private String makeGameAddress(String gameName) throws InternalError {
    try {
      String hashedNameSpace =
          Utils.hash512(xoNameSpace.getBytes("UTF-8")).substring(0, 6);
      String hashedName =
          Utils.hash512(gameName.getBytes("UTF-8")).substring(64, 128);
      return hashedNameSpace + hashedName;
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      throw new InternalError("Internal Error, " + e.toString());
    }
  }

  /**
   * Helper function to turn a list into a csv string.
   */
  private String toCsv(String[] gameData) {
    StringBuilder csv = new StringBuilder("");
    for (int i = 0; i < gameData.length; i++) {
      csv.append(gameData[i]);
      if (i < gameData.length - 1) {
        csv.append(",");
      }
    }
    return csv.toString();
  }

  /**
   * Helper function to decode csv-utf8 data.
   */
  private String[] csvDecodeData(String payload) {
    return payload.split(",");
  }

  /**
   * Function that handles game logic.
   */
  private String[] playXo(String name, String board, String state,
                          String player1, String player2,
                          String player, String action, String spaceStr)
      throws InvalidTransactionException, InternalError {
    char[] boardList = board.toCharArray();
    if (Arrays.asList("P1-WIN", "P2-WIN", "TIE").contains(state)) {
      throw new InvalidTransactionException("Invalid action: Game has ended");
    }

    switch (action) {
      case "create":
        if (!spaceStr.equals("")) {
          throw new InvalidTransactionException(
              "Invalid Action: Space was specified for 'create' action"
          );
        }
        if (!board.equals("")) {
          throw new InvalidTransactionException("Invalid Action: Game already exists");
        }

        board = "---------";
        state = "P1-NEXT";
        player1 = "";
        player2 = "";

        display(String.format("Player %s created a game", shortenString(player)));
        break;
      case "take":
        int space;
        try {
          space = Integer.parseInt(spaceStr);
        } catch (Exception e) {
          throw new InvalidTransactionException("Space could not be converted as an integer");
        }
        if (space < 1 || space > 9) {
          throw new InvalidTransactionException(String.format("Invalid space: %d", space));
        }
        if (board.equals("")) {
          throw new InvalidTransactionException("Invalid action: 'take' requires an existing game");
        }
        if (!Arrays.asList("P1-NEXT", "P2-NEXT").contains(state)) {
          throw new InternalError(String.format(
              "Internal Error: Game has reached an invalid state: %s", state
          ));
        }

        if (player1.equals("")) {
          player1 = player;
        } else if (player2.equals("")) {
          player2 = player;
        }

        if (boardList[space - 1] != '-') {
          throw new InvalidTransactionException("Space already taken");
        }

        if (state.equals("P1-NEXT") && player.equals(player1)) {
          boardList[space - 1] = 'X';
          state = "P2-NEXT";
        } else if (state.equals("P2-NEXT") && player.equals(player2)) {
          boardList[space - 1] = 'O';
          state = "P1-NEXT";
        } else {
          throw new InvalidTransactionException(String.format(
              "Not this player's turn: %s", shortenString(player)
          ));
        }
        state = determineState(boardList, state);
        board = String.valueOf(boardList);
        display(
            String.format("Player %s takes space %d", shortenString(player), space)
                + gameDataToString(board, state, player1, player2, name)
        );
        break;
      default:
        throw new InvalidTransactionException(String.format("Invalid Action: %s", action));
    }
    String[] updatedGameData = new String[4];
    updatedGameData[0] = board;
    updatedGameData[1] = state;
    updatedGameData[2] = player1;
    updatedGameData[3] = player2;
    return updatedGameData;
  }

  /**
   * Helper function that updates game state based on the current board position.
   */
  private String determineState(char[] boardList, String state) {
    if (isWin(boardList, 'X')) {
      state = "P1-WIN";
    } else if (isWin(boardList, 'O')) {
      state = "P2-WIN";
    } else if (!(String.valueOf(boardList).contains("-"))) {
      state = "TIE";
    }
    return state;
  }

  /**
   * Helper function that analyzes board position to determine if it is in a winning state.
   */
  private boolean isWin(char[] board, char letter) {
    int[][] wins = new int[][]{
        {1, 2, 3}, {4, 5, 6}, {7, 8, 9},
        {1, 4, 7}, {2, 5, 8}, {3, 6, 9},
        {1, 5, 9}, {3, 5, 7},
    };

    for (int[] win : wins) {
      if (board[win[0] - 1] == letter
          && board[win[1] - 1] == letter
          && board[win[2] - 1] == letter) {
        return true;
      }
    }
    return false;
  }

  /**
   * Helper function to create an ASCII representation of the board.
   */
  private String gameDataToString(
      String boardStr, String state,
      String player1, String player2, String name) {
    boardStr = boardStr.replace('-',' ');
    String out = "";
    out += String.format("GAME: %s\n", name);
    out += String.format("PLAYER 1: %s\n", shortenString(player1));
    out += String.format("PLAYER 2: %s\n", shortenString(player2));
    out += String.format("STATE: %s\n", state);
    out += "\n";

    char[] board = boardStr.toCharArray();
    out += String.format("%c | %c |  %c\n", board[0], board[1], board[2]);
    out += "---|---|---\n";
    out += String.format("%c | %c |  %c\n", board[3], board[4], board[5]);
    out += "---|---|---\n";
    out += String.format("%c | %c |  %c\n", board[6], board[7], board[8]);
    return out;
  }

  /**
   * Helper function to print game data to the logger.
   */
  private void display(String msg) {
    String displayMsg = "";
    int length = 0;
    String[] msgLines = msg.split("\n");
    if (msg.contains("\n")) {
      for (String line : msgLines) {
        if (line.length() > length) {
          length = line.length();
        }
      }
    } else {
      length = msg.length();
    }

    displayMsg = displayMsg.concat("\n+" + printDashes(length + 2) + "+\n");
    for (String line : msgLines) {
      displayMsg = displayMsg.concat("+" + StringUtils.center(line, length + 2) + "+\n");
    }
    displayMsg = displayMsg.concat("+" + printDashes(length + 2) + "+");

    logger.info(displayMsg);
  }

  /**
   * Helper function to create a string with a specified number of dashes (for logging purposes).
   */
  private String printDashes(int length) {
    String dashes = "";
    for (int i = 0; i < length; i++) {
      dashes = dashes.concat("-");
    }
    return dashes;
  }

  /**
   * Helper function to shorten a string to a max of 6 characters for logging purposes.
   */
  private Object shortenString(String player) {
    return player.substring(0, Math.min(player.length(), 6));
  }
}
