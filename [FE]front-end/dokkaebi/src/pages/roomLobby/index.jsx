import Background from "../../images/gamePlay/background6.gif";
import LobbyTop from "../../components/roomLobby/LobbyTop.jsx";
import PlayerList from "../../components/roomLobby/PlayerList.jsx";
import LobbyChat from "../../components/roomLobby/LobbyChat.jsx";

import React, { useRef, useEffect, useState } from "react";
import { Stomp } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import axios from "axios";

export default function userReadyRoom() {
  // 배경 GIF 설정
  const backgroundStyle = {
    backgroundImage: `url(${Background})`,
    backgroundSize: "cover",
    backgroundPosition: "center",
    width: "100%",
    height: "100%",
    position: "fixed",
    top: 0,
    left: 0,
  };
  const accessToken = sessionStorage.getItem("accessToken");
  const roomId = sessionStorage.getItem("roomId");

  const stompClientRef = useRef(null);

  const socketUrl = "https://j10d202.p.ssafy.io/ws-stomp";

  const [userList, setUserList] = useState([]); 

  useEffect(() => {
    let reconnectInterval;

    const connect = () => {
      const socket = new SockJS(socketUrl);
      const stompClient = Stomp.over(() => socket);
      stompClientRef.current = stompClient;

      stompClient.connect(
        {},
        function (frame) {
          stompClient.subscribe(`/sub/room/chat/${roomId}`, function (message) {
            const receivedMessage = JSON.parse(message.body);
            console.log(receivedMessage);
            console.log(receivedMessage.type);

            if (receivedMessage.type === "ROOM_ENTER") {
              console.log(receivedMessage.data);
              setUserList(receivedMessage.data);
            }
          });
        },
        function (error) {
          // 연결이 끊어졌을 때 재연결을 시도합니다.
          console.log("STOMP: Connection lost. Attempting to reconnect", error);
          reconnectInterval = setTimeout(connect, 1000); // 1초 후 재연결 시도
        }
      );
    };

    connect();

    return () => {
      console.log("unmounting...");
    console.log(stompClientRef.current);
    
    if (stompClientRef.current) {
      stompClientRef.current.disconnect();
      console.log("STOMP: Disconnected");
    }

      if (reconnectInterval) {
        clearTimeout(reconnectInterval);
      }
    };
  }, []);

  return (
    <div style={backgroundStyle}>
      <LobbyTop />
      {/* 로비에 들어온 유저 리스트와 로비 채팅 컨테이너 */}
      <div className="flex flex-col items-center">
        <PlayerList />
        <LobbyChat userList={userList}/>
      </div>
    </div>
  );
}
