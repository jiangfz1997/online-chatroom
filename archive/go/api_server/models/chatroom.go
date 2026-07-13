package models

type Chatroom struct {
	RoomID    string `json:"room_id"`
	Name      string `json:"name"`
	IsPrivate bool   `json:"is_private"`
	CreatedBy string `json:"created_by"`
}
