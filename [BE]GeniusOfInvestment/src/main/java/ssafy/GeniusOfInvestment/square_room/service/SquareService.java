package ssafy.GeniusOfInvestment.square_room.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import ssafy.GeniusOfInvestment._common.entity.Channel;
import ssafy.GeniusOfInvestment._common.entity.Room;
import ssafy.GeniusOfInvestment._common.entity.User;
import ssafy.GeniusOfInvestment._common.exception.CustomBadRequestException;
import ssafy.GeniusOfInvestment._common.redis.RedisUser;
import ssafy.GeniusOfInvestment._common.response.ErrorType;
import ssafy.GeniusOfInvestment._common.stomp.dto.MessageDto;
import ssafy.GeniusOfInvestment.game.repository.RedisGameRepository;
import ssafy.GeniusOfInvestment._common.redis.GameRoom;
import ssafy.GeniusOfInvestment._common.redis.GameUser;
import ssafy.GeniusOfInvestment.square_room.dto.request.RoomCreateRequest;
import ssafy.GeniusOfInvestment.square_room.dto.response.*;
import ssafy.GeniusOfInvestment.square_room.repository.ChannelRepository;
import ssafy.GeniusOfInvestment.square_room.repository.RedisUserRepository;
import ssafy.GeniusOfInvestment.square_room.repository.RoomRepository;
import ssafy.GeniusOfInvestment.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SquareService {

    private final RoomRepository roomRepository;
    private final RedisGameRepository redisGameRepository;
    private final RedisUserRepository redisUserRepository;
    private final ChannelRepository channelRepository;
    private final SimpMessageSendingOperations messageTemplate;
    private final UserRepository userRepository;

    public SavedRoomResponse insertRoom(User user, RoomCreateRequest info) {
        //저장할 채널 객체 생성
        Channel ch = new Channel();
        ch.setId(info.channelId());

        //방 객체 생성 및 사용자가 원하는 방제,비번등으로 설정
        Room room = Room
                .builder()
                .channel(ch)
                .title(info.title())
                .password(info.password())
                .isPublic(info.isPublic())
                .status(0)
                .fromYear(info.fromYear())
                .endYear(info.endYear())
                .build();

        //방 정보 DB 저장
        roomRepository.save(room);

        //Redis 정보속 유저 리스트 생성
        List<GameUser> list = new ArrayList<>();
        list.add(GameUser
                .builder()
                .userId(user.getId())
                .isReady(false)
                .isManager(true)
                .build());

        //Redis GameRoom 생성
        GameRoom gameRoom = GameRoom
                .builder()
                .id(room.getId())
                .participants(list)
                .build();

        redisGameRepository.saveGameRoom(gameRoom);

        return makeSavedRoomResponse(room);
        //방생성 완료
    }

    public void searchRoom(User user, Long roomnum) {
        //방 찾고 유저를 방안에 집어넣고 Websocket 연결

        //방찾기
        Optional<Room> finded = roomRepository.findById(roomnum);

        //방이없다면 ROOM_NOT_FOUND 에러 표시
        if(finded.isEmpty()) {
            throw new CustomBadRequestException(ErrorType.ROOM_NOT_FOUND);
        } else if (finded.get().getStatus()!=0) {
            throw new CustomBadRequestException(ErrorType.ROOM_NOT_FOUND);
        } else if (!Objects.equals(finded.get().getChannel().getId(), user.getChannel().getId()))
            throw new CustomBadRequestException(ErrorType.ROOM_NOT_FOUND);

        //유저를 방안에 집어 넣기
        //이미 있는 방정보 roomnum 즉 방 id로 가져오고
        //유저 한명 추가후 update
        GameRoom gameRoom = redisGameRepository.getOneGameRoom(roomnum);

        List<GameUser> participants = gameRoom.getParticipants();
        participants.add(GameUser
                .builder()
                .userId(user.getId())
                .isReady(false)
                .isManager(false)
                .build());
        gameRoom.setParticipants(participants);

        redisGameRepository.updateGameRoom(gameRoom);

        //유저 동선 추적
        RedisUser rdu = redisUserRepository.getOneRedisUser(user.getId());
        if(rdu == null) redisUserRepository.updateUserStatusGameing(new RedisUser(user.getId(), false));
        else throw new CustomBadRequestException(ErrorType.IS_NOT_AVAILABLE_REDISUSER);

        redisUserRepository.updateUserStatusGameing(rdu); //각 유저마다의 상태값을 변경

        // 웹소켓 연결
        messageTemplate.convertAndSend("/alram/msg-to/" + roomnum,
                MessageDto
                        .builder()
                        .type(MessageDto.MessageType.ROOM_ENTER)
                        .data(UserEnterMessageResponse
                                .builder()
                                .userId(user.getId())
                                .roomId(gameRoom.getId())
                                .chId(user.getChannel().getId())
                                .isReady(false)
                                .exp(user.getExp())
                                .nickName(user.getNickName())
                                .build())
                        .build());
    }

    public List<SquareNowUser> listUser(Long channelnum) {
        //리턴할 list
        List<SquareNowUser> list = new ArrayList<>();


        //받아온 방정보
        List<User> users = new ArrayList<>();
        try {
            Optional<Channel> byId = channelRepository.findById(channelnum);
            if(byId.isPresent())
                users = userRepository.findAllByChannel(byId.get());
        } catch (Exception e) {
            throw new CustomBadRequestException(ErrorType.CHANNEL_NOT_FOUND);
        }

        for (User u : users){
            int nowStatus; // 0 == 로그인 1 == 대기중 2 == 게임중
            if(redisUserRepository.getOneRedisUser(u.getId())==null) nowStatus=0; // 로그인 중
            else{
                if (redisUserRepository.getOneRedisUser(u.getId()).isStatus())
                    nowStatus = 2; // 게임 중
                else
                    nowStatus = 1; // 대기 중
            }


            list.add(SquareNowUser
                        .builder()
                            .id(u.getId())
                            .nickName(u.getNickName())
                            .status(nowStatus)
                            .exp(u.getExp())
                            .imageId(u.getImageId())
                        .build());
        }

        return list;
    }

    public List<SquareRoom> listRoom(Long channelnum) {

        //채널 잘못 받을때 예외
        if (channelnum>8 || channelnum<1)
            throw new CustomBadRequestException(ErrorType.NOT_AVAILABLE_CHANNEL);

        //리턴할 list
        List<SquareRoom> list =new ArrayList<>();

        //받아온 방정보
        List<SquareRoom> rooms = channelRepository.findRoomsStatus0(channelnum);

        //list 가공
        for (SquareRoom room : rooms) {
            Long id = room.id();
            list.add(SquareRoom
                    .builder()
                    .id(id)
                    .title(room.title())
                    .isPublic(room.isPublic())
                    .userCount(redisGameRepository.getOneGameRoom(id).getParticipants().size())
                    .build());
        }
        return list;
    }

    public SavedRoomResponse makeSavedRoomResponse(Room room){
        return SavedRoomResponse
                .builder()
                .roomnum(room.getId())
                .channelId(room.getChannel().getId())
                .title(room.getTitle())
                .password(room.getPassword())
                .isPublic(room.isPublic())
                .status(room.getStatus())
                .fromYear(room.getFromYear())
                .endYear(room.getEndYear())
                .build();
    }


}
