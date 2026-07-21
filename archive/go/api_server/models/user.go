package models

type User struct {
	Username string `dynamodbav:"username"` //primary key
	Password string `dynamodbav:"password"`
}
