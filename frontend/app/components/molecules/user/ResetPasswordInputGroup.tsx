"use client";

import ResetPasswordInputField from "../../atoms/user/ResetPasswordInputField";
import styled from "@emotion/styled";

const Container = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: 20px 0px;
`;

const ResetPasswordInputGroup = ({ setPassword1, setPassword2 }) => {
  return (
    <>
      <Container>
        <ResetPasswordInputField
          type="password"
          placeholder="새 비밀번호"
          setPassword={setPassword1}
        />
        <ResetPasswordInputField
          type="password"
          placeholder="새 비밀번호 확인"
          setPassword={setPassword2}
        />
      </Container>
    </>
  );
};

export default ResetPasswordInputGroup;
